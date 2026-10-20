#!/usr/bin/env python3
"""Builds Free My TV for LG webOS: FreeMyTV-webos.ipk, the app package the TV's Developer Mode installs.

    python3 webos/build.py            # → FreeMyTV-webos.ipk
    python3 webos/build.py --preview  # also leaves the files in out/webos/ to open index.html in a browser
    python3 webos/build.py --config other.properties
    python3 webos/build.py --generic  # without config.properties: the TV's time zone picks the city (releases)
    python3 webos/build.py --apps apps.json  # paired TV inventory when app enumeration is denied

Reads config.properties (the same file as the Android build), puts the web home screen (web/), the app's symbols
and font (app/assets), the coin logos (app/res/drawable) and webos/appinfo.json together, and packs them the way LG's
ares-package does (an ar archive with control.tar.gz and data.tar.gz), so no LG tools are needed. Python 3 only.
"""
import io
import json
import os
import re
import sys
import tarfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import webapp  # noqa: E402

OUT = os.path.join(ROOT, 'out', 'webos')
IPK = os.path.join(ROOT, 'FreeMyTV-webos.ipk')


def validate_apps(raw):
    """Keep only app metadata; never package addresses, pairing keys or remote icon URLs."""
    if not isinstance(raw, list) or len(raw) > 512:
        raise ValueError('The app inventory must be a list of at most 512 apps.')
    apps, seen = [], set()
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError('Each inventory entry must be an app object.')
        app_id = item.get('id')
        if not isinstance(app_id, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,255}', app_id):
            raise ValueError('The inventory contains an invalid app ID.')
        if item.get('visible') is False or app_id == 'io.freemytv' or app_id in seen:
            continue
        title = item.get('title') or app_id
        if not isinstance(title, str) or len(title) > 200:
            raise ValueError('App titles must be strings of at most 200 characters.')
        color = item.get('color') or item.get('bgColor') or item.get('iconColor') or ''
        if not isinstance(color, str) or not re.fullmatch(r'#[0-9A-Fa-f]{6}', color):
            color = ''
        apps.append({'id': app_id, 'title': title, 'color': color})
        seen.add(app_id)
    return apps


def apps_from_args(args):
    """Personal builds use the local inventory; generic releases never include it."""
    if '--generic' in args:
        if '--apps' in args:
            raise ValueError('--generic cannot be combined with --apps.')
        return []
    if '--apps' in args:
        index = args.index('--apps') + 1
        if index >= len(args) or args[index].startswith('--'):
            raise ValueError('--apps requires an inventory JSON path.')
        path = os.path.abspath(args[index])
    else:
        path = os.path.join(ROOT, 'webos', 'apps.local.json')
        if not os.path.exists(path):
            return []
    with open(path, encoding='utf-8') as f:
        return validate_apps(json.load(f))


def tar_gz(entries):
    """A .tar.gz from [(name, bytes or None for a directory)], owned by root, as ares-package makes them."""
    buf = io.BytesIO()
    now = int(time.time())
    with tarfile.open(fileobj=buf, mode='w:gz', format=tarfile.PAX_FORMAT) as tar:
        for name, data in entries:
            info = tarfile.TarInfo(name)
            info.mtime = now
            info.uid = info.gid = 0
            if data is None:
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                tar.addfile(info)
            else:
                info.size = len(data)
                info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def ar(members):
    out = io.BytesIO()
    out.write(b'!<arch>\n')
    now = int(time.time())
    for name, data in members:
        out.write(('%-16s%-12d%-6d%-6d%-8s%-10d`\n' % (name, now, 0, 0, '100644', len(data))).encode('ascii'))
        out.write(data)
        if len(data) % 2:
            out.write(b'\n')
    return out.getvalue()


def build(preview, config, apps=None):
    with open(os.path.join(ROOT, 'webos', 'appinfo.json'), encoding='utf-8') as f:
        info = json.load(f)
    app_id, version = info['id'], info['version']
    config = dict(config)
    if apps:
        config['webosApps'] = validate_apps(apps)
    app = webapp.files(config, 'webos/build.py')
    for name in ('appinfo.json', 'icon.png', 'largeIcon.png'):
        with open(os.path.join(ROOT, 'webos', name), 'rb') as f:
            app[name] = f.read()

    base = 'usr/palm/applications/' + app_id
    entries = [('usr', None), ('usr/palm', None), ('usr/palm/applications', None), (base, None)]
    dirs = set()
    for path in sorted(app):
        parent = os.path.dirname(path)
        if parent and parent not in dirs:
            dirs.add(parent)
            entries.append((base + '/' + parent, None))
        entries.append((base + '/' + path, app[path]))
    package = json.dumps({'id': app_id, 'version': version, 'app': app_id}, indent=2).encode('utf-8')
    entries += [('usr/palm/packages', None), ('usr/palm/packages/' + app_id, None),
                ('usr/palm/packages/' + app_id + '/packageinfo.json', package)]

    size = sum(len(d) for d in app.values()) + len(package)
    control = ('Package: %s\nVersion: %s\nSection: misc\nPriority: optional\nArchitecture: all\nInstalled-Size: %d\n'
               'Maintainer: Free My TV <https://freemytv.io>\nDescription: A fast, ad-free home screen.\n'
               'webOS-Package-Format-Version: 2\nwebOS-Packager-Version: 1.0.0\n' % (app_id, version, size))
    data = ar([('debian-binary', b'2.0\n'), ('control.tar.gz', tar_gz([('control', control.encode('utf-8'))])),
               ('data.tar.gz', tar_gz(entries))])
    with open(IPK, 'wb') as f:
        f.write(data)

    if preview:
        webapp.preview(app, OUT)
        print('Preview: open out/webos/index.html (or serve out/webos) in a browser.')
    print('Built %s (%s %s, %d KB)' % (os.path.basename(IPK), app_id, version, len(data) // 1024))
    if not config['tmdbKey']:
        print('No TMDB key: the picks stay off (everything else works).')


if __name__ == '__main__':
    try:
        inventory = apps_from_args(sys.argv[1:])
    except (OSError, ValueError) as e:
        sys.exit('Could not load app inventory: ' + str(e))
    build('--preview' in sys.argv, webapp.config_from_args(sys.argv[1:]), inventory)
