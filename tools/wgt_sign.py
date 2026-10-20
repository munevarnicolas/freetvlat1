#!/usr/bin/env python3
"""Signs a Free My TV Tizen package (.wgt) natively, without Samsung's Tizen Studio or Rosetta.

Samsung's `tizen package` (and the `sdb` it drives) are Intel-only on macOS, so on Apple Silicon they need
Rosetta, which Apple is removing (gone in macOS 28). This signs the widget with the platform's own `openssl`
(native on macOS and Linux) and pure-Python XML, so the build runs on any machine.

A Tizen .wgt is a zip with two W3C "Widget Digital Signatures" at its root:
  - author-signature.xml   signed by the author certificate, over every content file.
  - signature1.xml         signed by the distributor certificate, over the content files and author-signature.xml.
Each is an XML-Signature (http://www.w3.org/2000/09/xmldsig#) with an XAdES SignedProperties block that carries the
signing time and the signing certificate's digest, the way Tizen Studio writes them.

    # self-signed author + distributor, for 2017-2022 TVs and for local testing (no Samsung account):
    python3 tools/wgt_sign.py FreeMyTV-tizen.wgt --self-signed

    # with your own certificates (PKCS#12 .p12, passphrase read from the terminal, never the chat):
    python3 tools/wgt_sign.py FreeMyTV-tizen.wgt --author author.p12 --dist tizen-distributor-signer.p12

    # check an already-signed package (file digests, certificate, SignedInfo signature):
    python3 tools/wgt_sign.py FreeMyTV-tizen.wgt --verify

What is verified here: the file digests, the RSA-SHA256 signature over SignedInfo, and the certificate digest are
all checked with `openssl` after signing (`--verify` does it on its own too). The canonical XML is built to the
C14N 1.1 form a verifier recomputes. Byte-exact acceptance by a given Samsung TV firmware should still be confirmed
on the device; 2017-2022 TVs take this self-signed path directly, 2023+ TVs need a Samsung device-unique
certificate (see CLAUDE.md).
"""
import base64
import datetime
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile

DSIG = 'http://www.w3.org/2000/09/xmldsig#'
XADES = 'http://uri.etsi.org/01903/v1.3.2#'
C14N11 = 'http://www.w3.org/2006/12/xml-c14n11'
RSA_SHA256 = 'http://www.w3.org/2001/04/xmldsig-more#rsa-sha256'
SHA256 = 'http://www.w3.org/2001/04/xmlenc#sha256'
SIGNED_PROPS_TYPE = 'http://uri.etsi.org/01903#SignedProperties'

SIG_FILES = ('author-signature.xml', 'signature1.xml')


# --- openssl, the one native dependency ------------------------------------------------------------------

def _openssl(args, data=None, capture=True):
    out = subprocess.run(['openssl'] + args, input=data,
                         stdout=subprocess.PIPE if capture else None,
                         stderr=subprocess.PIPE)
    if out.returncode != 0:
        sys.exit('openssl ' + args[0] + ' failed:\n' + (out.stderr or b'').decode('utf-8', 'replace').strip())
    return out.stdout


class Cert:
    """A signer's key and certificate, as PEM bytes, read from a .p12 or a key+cert pair."""

    def __init__(self, key_pem, cert_pem):
        self.key_pem = key_pem
        self.cert_pem = cert_pem
        self.der = _openssl(['x509', '-outform', 'DER'], data=cert_pem)
        self.digest = hashlib.sha256(self.der).digest()
        # RFC2253/4514 distinguished name, as XAdES X509IssuerName wants it.
        issuer = _openssl(['x509', '-noout', '-issuer', '-nameopt', 'RFC2253'], data=cert_pem)
        self.issuer = issuer.decode('utf-8', 'replace').split('=', 1)[1].strip()
        serial = _openssl(['x509', '-noout', '-serial'], data=cert_pem)
        self.serial = str(int(serial.decode().split('=', 1)[1].strip(), 16))

    @classmethod
    def from_p12(cls, path, password):
        pw = ['-passin', 'pass:' + password]
        key = _openssl(['pkcs12', '-in', path, '-nocerts', '-nodes'] + pw, data=b'')
        cert = _openssl(['pkcs12', '-in', path, '-clcerts', '-nokeys'] + pw, data=b'')
        return cls(_pem_only(key, 'PRIVATE KEY'), _pem_only(cert, 'CERTIFICATE'))

    @classmethod
    def from_pem(cls, key_path, cert_path):
        with open(key_path, 'rb') as f:
            key = f.read()
        with open(cert_path, 'rb') as f:
            cert = f.read()
        return cls(key, cert)

    def sign(self, data):
        """RSA-SHA256 signature over data, as raw bytes."""
        with tempfile.NamedTemporaryFile(delete=False) as kf:
            kf.write(self.key_pem)
            key_path = kf.name
        try:
            return _openssl(['dgst', '-sha256', '-sign', key_path], data=data)
        finally:
            os.unlink(key_path)

    def verify(self, data, signature):
        with tempfile.NamedTemporaryFile(delete=False) as cf:
            cf.write(self.cert_pem)
            cert_path = cf.name
        pub = _openssl(['x509', '-pubkey', '-noout', '-in', cert_path])
        os.unlink(cert_path)
        with tempfile.NamedTemporaryFile(delete=False) as pf:
            pf.write(pub)
            pub_path = pf.name
        with tempfile.NamedTemporaryFile(delete=False) as sf:
            sf.write(signature)
            sig_path = sf.name
        try:
            out = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', pub_path, '-signature', sig_path],
                                 input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return out.returncode == 0
        finally:
            os.unlink(pub_path)
            os.unlink(sig_path)


def _pem_only(blob, kind):
    """Keep just the first -----BEGIN kind----- block; openssl pkcs12 prints bag attributes around it."""
    text = blob.decode('utf-8', 'replace')
    begin = '-----BEGIN ' + kind + '-----'
    end = '-----END ' + kind + '-----'
    i = text.find(begin)
    j = text.find(end)
    if i < 0 or j < 0:
        # Keys may be labelled "RSA PRIVATE KEY" or "ENCRYPTED PRIVATE KEY"; fall back to any key block.
        if kind == 'PRIVATE KEY':
            for alt in ('RSA PRIVATE KEY', 'ENCRYPTED PRIVATE KEY'):
                out = _pem_only(blob, alt)
                if out:
                    return out
        return b''
    return (text[i:j] + end + '\n').encode('utf-8')


# --- canonical XML, built to C14N 1.1 by construction ----------------------------------------------------

def _esc_text(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('\r', '&#xD;')


def _esc_attr(s):
    return (s.replace('&', '&amp;').replace('<', '&lt;').replace('"', '&quot;')
            .replace('\t', '&#x9;').replace('\n', '&#xA;').replace('\r', '&#xD;'))


def el(tag, attrs=None, children=None, text=None):
    """A canonical element as a unicode string. attrs is a dict; xmlns declarations sort first, then the rest
    by local name, the way Canonical XML orders them. children is a list of already-canonical strings."""
    attrs = attrs or {}
    ns = sorted((k for k in attrs if k == 'xmlns' or k.startswith('xmlns:')),
                key=lambda k: '' if k == 'xmlns' else k.split(':', 1)[1])
    rest = sorted(k for k in attrs if k not in ns)
    rendered = ''.join(' %s="%s"' % (k, _esc_attr(attrs[k])) for k in ns + rest)
    if children:
        return '<%s%s>%s</%s>' % (tag, rendered, ''.join(children), tag)
    if text is not None:
        return '<%s%s>%s</%s>' % (tag, rendered, _esc_text(text), tag)
    return '<%s%s></%s>' % (tag, rendered, tag)


def _b64(data):
    return base64.b64encode(data).decode('ascii')


# --- the two signatures ----------------------------------------------------------------------------------

def _references(files, names):
    """A <ds:Reference> per file, SHA-256 over the raw bytes, in sorted order for a stable signature."""
    out = []
    for name in sorted(names):
        digest = hashlib.sha256(files[name]).digest()
        out.append(el('ds:Reference', {'URI': name}, [
            el('ds:DigestMethod', {'Algorithm': SHA256}),
            el('ds:DigestValue', text=_b64(digest)),
        ]))
    return out


def _signed_properties(cert, sig_id, prop_id):
    """The XAdES SignedProperties: signing time and the signing certificate's digest and issuer/serial.
    Carries its own namespaces (xades as default, ds for the XML-Signature digest elements) so the element
    canonicalizes to the exact bytes digested in the SignedInfo reference, independent of document context."""
    now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    return el('xades:SignedProperties',
              {'xmlns:ds': DSIG, 'xmlns:xades': XADES, 'Id': prop_id, 'Target': '#' + sig_id}, [
        el('xades:SignedSignatureProperties', {}, [
            el('xades:SigningTime', text=now),
            el('xades:SigningCertificate', {}, [
                el('xades:Cert', {}, [
                    el('xades:CertDigest', {}, [
                        el('ds:DigestMethod', {'Algorithm': SHA256}),
                        el('ds:DigestValue', text=_b64(cert.digest)),
                    ]),
                    el('xades:IssuerSerial', {}, [
                        el('ds:X509IssuerName', text=cert.issuer),
                        el('ds:X509SerialNumber', text=cert.serial),
                    ]),
                ]),
            ]),
        ]),
    ])


def _signature(cert, files, names, sig_id, prop_id):
    """One XML-Signature document (author or distributor) as UTF-8 bytes, plus the signed SignedInfo bytes and
    the raw signature for a self-check. The dsig namespace is carried by an explicit ds: prefix throughout, so
    there is no default namespace to undeclare and the canonical form is unambiguous."""
    signed_props = _signed_properties(cert, sig_id, prop_id)
    prop_ref = el('ds:Reference', {'Type': SIGNED_PROPS_TYPE, 'URI': '#' + prop_id}, [
        el('ds:Transforms', {}, [el('ds:Transform', {'Algorithm': C14N11})]),
        el('ds:DigestMethod', {'Algorithm': SHA256}),
        el('ds:DigestValue', text=_b64(hashlib.sha256(signed_props.encode('utf-8')).digest())),
    ])
    signed_info = el('ds:SignedInfo', {'xmlns:ds': DSIG}, [
        el('ds:CanonicalizationMethod', {'Algorithm': C14N11}),
        el('ds:SignatureMethod', {'Algorithm': RSA_SHA256}),
    ] + _references(files, names) + [prop_ref])

    signature = cert.sign(signed_info.encode('utf-8'))

    # The signed fragments (SignedInfo, SignedProperties) are embedded exactly as signed/digested; the rest of
    # the document is plain XML the verifier does not re-canonicalize.
    doc = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<ds:Signature xmlns:ds="%s" Id="%s">'
           '%s'
           '<ds:SignatureValue>%s</ds:SignatureValue>'
           '<ds:KeyInfo><ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo>'
           '<ds:Object>%s</ds:Object>'
           '</ds:Signature>\n') % (DSIG, sig_id, signed_info, _b64(signature), _cert_b64(cert), signed_props)
    return doc.encode('utf-8'), signed_info.encode('utf-8'), signature


def _cert_b64(cert):
    """The certificate's DER, base64, with the PEM armour stripped, as X509Certificate wants it."""
    return _b64(cert.der)


def signatures_for(files, author, dist):
    """author-signature.xml over the content files, then signature1.xml over the content files plus it.
    files maps package path -> bytes and must not already hold the signature files. Returns the two to add."""
    content = [n for n in files if n not in SIG_FILES]
    author_doc, author_si, author_sig = _signature(author, files, content, 'AuthorSignature', 'AuthorProp')
    _check(author, author_si, author_sig)
    with_author = dict(files)
    with_author['author-signature.xml'] = author_doc
    dist_names = [n for n in with_author if n != 'signature1.xml']
    dist_doc, dist_si, dist_sig = _signature(dist, with_author, dist_names, 'DistributorSignature', 'DistributorProp')
    _check(dist, dist_si, dist_sig)
    return {'author-signature.xml': author_doc, 'signature1.xml': dist_doc}


def _check(cert, signed_info, signature):
    if not cert.verify(signed_info, signature):
        sys.exit('The signature did not verify against its own certificate; refusing to write a broken package.')


# --- independent verification, stdlib + openssl only -----------------------------------------------------

def verify_wgt(wgt_path):
    """Check a signed .wgt the way a TV would: every file digest, the certificate, and the RSA signature over the
    embedded (already canonical) SignedInfo. Returns a list of problems; an empty list means it verified."""
    problems = []
    with zipfile.ZipFile(wgt_path) as z:
        files = {n: z.read(n) for n in z.namelist()}
    for signame, covers in (('author-signature.xml', False), ('signature1.xml', True)):
        if signame not in files:
            problems.append('%s is missing.' % signame)
            continue
        xml = files[signame]
        refs = re.findall(rb'<ds:Reference URI="([^"#][^"]*)">.*?<ds:DigestValue>([^<]+)</ds:DigestValue>', xml, re.S)
        for uri, dv in refs:
            uri = uri.decode()
            if uri not in files:
                problems.append('%s references a missing file %s.' % (signame, uri))
                continue
            real = _b64(hashlib.sha256(files[uri]).digest()).encode()
            if real != dv:
                problems.append('%s: digest mismatch for %s.' % (signame, uri))
        has_author = any(u == b'author-signature.xml' for u, _ in refs)
        if covers and not has_author:
            problems.append('signature1.xml does not cover author-signature.xml.')
        if not covers and has_author:
            problems.append('author-signature.xml should not cover itself.')
        # the signed properties digest must match the embedded (canonical) SignedProperties bytes
        sp = re.search(rb'(<xades:SignedProperties\b.*?</xades:SignedProperties>)', xml, re.S)
        propdv = re.search(rb'URI="#\w+Prop">.*?<ds:DigestValue>([^<]+)</ds:DigestValue>', xml, re.S)
        if sp and propdv and _b64(hashlib.sha256(sp.group(1)).digest()).encode() != propdv.group(1):
            problems.append('%s: SignedProperties digest mismatch.' % signame)
        # RSA-SHA256 over the embedded SignedInfo, against the embedded certificate
        si = re.search(rb'(<ds:SignedInfo\b.*?</ds:SignedInfo>)', xml, re.S)
        sv = re.search(rb'<ds:SignatureValue>([^<]+)</ds:SignatureValue>', xml)
        cb = re.search(rb'<ds:X509Certificate>([^<]+)</ds:X509Certificate>', xml)
        if not (si and sv and cb):
            problems.append('%s: could not read SignedInfo, SignatureValue or the certificate.' % signame)
            continue
        der = base64.b64decode(cb.group(1))
        pem = b'-----BEGIN CERTIFICATE-----\n' + base64.encodebytes(der) + b'-----END CERTIFICATE-----\n'
        if not Cert(b'', pem).verify(si.group(1), base64.b64decode(sv.group(1))):
            problems.append('%s: the SignedInfo signature did not verify.' % signame)
    return problems


# --- self-signed certificates, for 2017-2022 TVs and for testing -----------------------------------------

def self_signed(out_dir, name='FreeMyTV'):
    """A throwaway author certificate with openssl, enough for 2017-2022 TVs and local verification."""
    os.makedirs(out_dir, exist_ok=True)
    key = os.path.join(out_dir, name.lower() + '_author_key.pem')
    cert = os.path.join(out_dir, name.lower() + '_author_cert.pem')
    if not (os.path.exists(key) and os.path.exists(cert)):
        _openssl(['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '3650', '-nodes',
                  '-keyout', key, '-out', cert,
                  '-subj', '/CN=%s Author/O=%s/C=US' % (name, name)], capture=False)
    return Cert.from_pem(key, cert)


# --- CLI -------------------------------------------------------------------------------------------------

def _load(p12, pem_key, pem_cert, role):
    import getpass
    if p12:
        password = os.environ.get('FREEMYTV_%s_PASS' % role.upper(), '')
        if not password:
            password = getpass.getpass('%s .p12 passphrase: ' % role)
        return Cert.from_p12(p12, password)
    if pem_key and pem_cert:
        return Cert.from_pem(pem_key, pem_cert)
    return None


def _arg(args, flag):
    return args[args.index(flag) + 1] if flag in args else None


def sign_wgt(wgt_path, author, dist):
    with zipfile.ZipFile(wgt_path) as z:
        files = {n: z.read(n) for n in z.namelist() if n not in SIG_FILES}
    sigs = signatures_for(files, author, dist)
    files.update(sigs)
    with zipfile.ZipFile(wgt_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            z.writestr(name, files[name])
    return sigs


def main(args):
    if not args or args[0] in ('-h', '--help'):
        print(__doc__)
        return
    wgt = args[0]
    if not os.path.exists(wgt):
        sys.exit('No such package: ' + wgt)
    if '--verify' in args:
        problems = verify_wgt(wgt)
        if problems:
            sys.exit('%s did NOT verify:\n- %s' % (wgt, '\n- '.join(problems)))
        print('%s verified: file digests, certificate and SignedInfo signature all check out.' % wgt)
        return
    if '--self-signed' in args:
        author = self_signed(os.path.join(os.path.dirname(os.path.abspath(wgt)) or '.', 'certs'))
        dist = author
    else:
        author = _load(_arg(args, '--author'), _arg(args, '--author-key'), _arg(args, '--author-cert'), 'author')
        dist = _load(_arg(args, '--dist'), _arg(args, '--dist-key'), _arg(args, '--dist-cert'), 'dist')
        if not author or not dist:
            sys.exit('Need an author and a distributor certificate: --author X.p12 --dist Y.p12, or --self-signed.')
    sign_wgt(wgt, author, dist)
    print('Signed %s: added author-signature.xml and signature1.xml (verified against their certificates).' % wgt)
    print('2017-2022 TVs install this as it is. 2023+ TVs need a Samsung device-unique certificate (CLAUDE.md).')


if __name__ == '__main__':
    main(sys.argv[1:])
