#!/usr/bin/env python3
"""A small native SDB client for Samsung Tizen Developer Mode TVs.

Samsung's bundled sdb binary is Intel-only on macOS. This stdlib-only client
speaks the ADB-like SDB TCP protocol directly, so the Tizen build and install
flow works on Apple Silicon without Rosetta. It covers the small SDB subset
used here: connecting, shell commands, file pushes and capability queries.
"""
import os
import socket
import struct
import sys
import time
import zlib


CNXN = 0x4e584e43
OPEN = 0x4e45504f
OKAY = 0x59414b4f
WRTE = 0x45545257
CLSE = 0x45534c43
AUTH = 0x48545541

VERSION = 0x01000000
MAX_DATA = 256 * 1024
PORT = 26101
TIMEOUT = 10

LAST_DEVICE = None


class SdbError(Exception):
    """An SDB connection, protocol or TV-side error."""


def _name(command):
    return struct.pack('<I', command).decode('ascii', 'replace')


def _sync_name(name):
    return name.encode('ascii')


def _quote(value):
    """A POSIX shell argument for the documented installer commands."""
    return "'" + str(value).replace("'", "'\\''") + "'"


class Sdb:
    """One direct TCP connection to a TV's SDB service."""

    def __init__(self, timeout=TIMEOUT):
        self.timeout = timeout
        self.sock = None
        self.ip = None
        self.banner = ''
        self._next_local_id = 1

    def connect(self, ip, port=PORT):
        """Connect and perform the SDB CNXN handshake. Returns the TV banner."""
        global LAST_DEVICE
        self.close()
        self.ip = ip
        try:
            self.sock = socket.create_connection((ip, port), self.timeout)
            self.sock.settimeout(self.timeout)
            self._send(CNXN, VERSION, MAX_DATA, b'host::\0')
            command, arg0, arg1, payload = self._receive()
        except (OSError, SdbError) as e:
            self.close()
            raise SdbError('Could not connect to %s:%d: %s' % (ip, port, e))
        if command == AUTH:
            self.close()
            raise SdbError('The TV requested SDB authentication. In Samsung Developer Mode, set Host PC IP to this '
                           'computer\'s IP, then restart the TV and try again.')
        if command != CNXN:
            self.close()
            raise SdbError('Expected CNXN from the TV, got %s.' % _name(command))
        self.banner = payload.rstrip(b'\0').decode('utf-8', 'replace')
        LAST_DEVICE = (ip, self.banner)
        return self.banner

    def close(self):
        """Close the TCP connection. It is safe to call more than once."""
        if self.sock is not None:
            try:
                self.sock.close()
            finally:
                self.sock = None

    def shell(self, command):
        """Run command through shell: and return its output as text."""
        return self._stream(b'shell:' + command.encode('utf-8') + b'\0').decode('utf-8', 'replace')

    def capability(self):
        """Return the TV's capability service output as text."""
        return self._stream(b'capability:\0').decode('utf-8', 'replace')

    def push(self, local, remote):
        """Copy local to remote through SDB's sync service."""
        if not os.path.isfile(local):
            raise SdbError('Local file not found: %s' % local)
        local_id, remote_id = self._open(b'sync:\0')
        try:
            mode = 0o100644
            try:
                mode = 0o100000 | (os.stat(local).st_mode & 0o777)
            except OSError:
                pass
            destination = ('%s,%d' % (remote, mode)).encode('utf-8')
            self._sync_write(local_id, remote_id, b'SEND', destination)
            with open(local, 'rb') as f:
                while True:
                    data = f.read(64 * 1024)
                    if not data:
                        break
                    self._sync_write(local_id, remote_id, b'DATA', data)
            self._sync_write(local_id, remote_id, b'DONE', struct.pack('<I', int(time.time())))
            self._sync_status(local_id, remote_id)
        finally:
            self._close_stream(local_id, remote_id)

    def _send(self, command, arg0, arg1, payload=b''):
        if self.sock is None:
            raise SdbError('Not connected.')
        payload = bytes(payload)
        header = struct.pack('<6I', command, arg0, arg1, len(payload), zlib.crc32(payload) & 0xffffffff,
                             command ^ 0xffffffff)
        self._sendall(header + payload)

    def _receive(self):
        header = self._read_exact(24)
        command, arg0, arg1, length, crc, magic = struct.unpack('<6I', header)
        if magic != (command ^ 0xffffffff):
            raise SdbError('Invalid SDB packet magic.')
        if length > MAX_DATA:
            raise SdbError('SDB packet is too large (%d bytes).' % length)
        payload = self._read_exact(length)
        if zlib.crc32(payload) & 0xffffffff != crc:
            raise SdbError('Invalid SDB packet checksum.')
        return command, arg0, arg1, payload

    def _sendall(self, data):
        try:
            self.sock.sendall(data)
        except socket.timeout:
            raise SdbError('Timed out while sending to the TV.')
        except OSError as e:
            raise SdbError('Connection to the TV failed: %s' % e)

    def _read_exact(self, length):
        data = bytearray()
        while len(data) < length:
            try:
                piece = self.sock.recv(length - len(data))
            except socket.timeout:
                raise SdbError('Timed out waiting for the TV.')
            except OSError as e:
                raise SdbError('Connection to the TV failed: %s' % e)
            if not piece:
                raise SdbError('The TV closed the SDB connection.')
            data.extend(piece)
        return bytes(data)

    def _open(self, destination):
        local_id = self._next_local_id
        self._next_local_id += 1
        self._send(OPEN, local_id, 0, destination)
        while True:
            command, arg0, arg1, payload = self._receive()
            if command == AUTH:
                raise SdbError('The TV requested SDB authentication. Check Developer Mode Host PC IP and restart the TV.')
            if command == OKAY and arg1 == local_id:
                return local_id, arg0
            if command == CLSE and arg1 == local_id:
                raise SdbError('The TV refused service %s.' % destination.rstrip(b'\0').decode('utf-8', 'replace'))
            raise SdbError('Expected OKAY while opening a service, got %s.' % _name(command))

    def _stream(self, destination):
        local_id, remote_id = self._open(destination)
        output = bytearray()
        closed = False
        try:
            while True:
                command, arg0, arg1, payload = self._receive()
                if command == WRTE and arg0 == remote_id and arg1 == local_id:
                    output.extend(payload)
                    self._send(OKAY, local_id, remote_id)
                elif command == CLSE and arg0 == remote_id and arg1 == local_id:
                    self._send(CLSE, local_id, remote_id)
                    closed = True
                    return bytes(output)
                else:
                    raise SdbError('Unexpected %s while reading a service.' % _name(command))
        finally:
            if not closed:
                self._close_stream(local_id, remote_id)

    def _write(self, local_id, remote_id, data):
        self._send(WRTE, local_id, remote_id, data)
        command, arg0, arg1, payload = self._receive()
        if command != OKAY or arg0 != remote_id or arg1 != local_id:
            raise SdbError('Expected OKAY after writing, got %s.' % _name(command))

    def _sync_write(self, local_id, remote_id, command, payload):
        self._write(local_id, remote_id, struct.pack('<4sI', command, len(payload)) + payload)

    def _sync_status(self, local_id, remote_id):
        data = bytearray()
        while len(data) < 8:
            command, arg0, arg1, payload = self._receive()
            if command == WRTE and arg0 == remote_id and arg1 == local_id:
                data.extend(payload)
                self._send(OKAY, local_id, remote_id)
            elif command == CLSE and arg0 == remote_id and arg1 == local_id:
                raise SdbError('The TV closed the sync service before reporting the file status.')
            else:
                raise SdbError('Unexpected %s from the sync service.' % _name(command))
        status, length = struct.unpack('<4sI', bytes(data[:8]))
        while len(data) < 8 + length:
            command, arg0, arg1, payload = self._receive()
            if command != WRTE or arg0 != remote_id or arg1 != local_id:
                raise SdbError('Unexpected %s from the sync service.' % _name(command))
            data.extend(payload)
            self._send(OKAY, local_id, remote_id)
        message = bytes(data[8:8 + length]).decode('utf-8', 'replace')
        if status == b'OKAY':
            return
        if status == b'FAIL':
            raise SdbError('The TV could not copy the file: %s' % message)
        raise SdbError('Unexpected sync status %r: %s' % (status, message))

    def _close_stream(self, local_id, remote_id):
        if self.sock is None:
            return
        try:
            self._send(CLSE, local_id, remote_id)
        except SdbError:
            pass


# These documented commands need on-device verification across Tizen versions.
def install_wgt(ip, wgt_path, pkgid):
    """Push wgt_path to the TV and install it. Returns the installer output."""
    name = os.path.basename(wgt_path)
    client = Sdb()
    try:
        client.connect(ip)
        errors = []
        for directory in ('/home/owner/share/tmp/sdk_tools/tmp/', '/opt/usr/home/owner/share/tmp/sdk_tools/tmp/'):
            remote = directory + name
            try:
                client.push(wgt_path, remote)
                output = client.shell('0 vd-appinstall %s %s' % (_quote(pkgid), _quote(remote)))
                if 'not found' not in output.lower():
                    return output
                fallback = client.shell('pkgcmd -i -t wgt -q -p %s' % _quote(remote))
                return output + fallback
            except SdbError as e:
                errors.append(str(e))
        raise SdbError('Could not copy the WGT to either documented SDK temp directory: %s' % '; '.join(errors))
    finally:
        client.close()


# These documented commands need on-device verification across Tizen versions.
def uninstall_pkg(ip, pkgid):
    """Uninstall pkgid and return the TV's output."""
    client = Sdb()
    try:
        client.connect(ip)
        output = client.shell('0 vd-appuninstall %s' % _quote(pkgid))
        if 'not found' in output.lower():
            output += client.shell('pkgcmd -u -n %s' % _quote(pkgid))
        return output
    finally:
        client.close()


# These documented commands need on-device verification across Tizen versions.
def launch_app(ip, appid):
    """Launch appid and return the TV's output."""
    client = Sdb()
    try:
        client.connect(ip)
        output = client.shell('0 was_execute %s' % _quote(appid))
        if 'not found' in output.lower():
            output += client.shell('app_launcher -s %s' % _quote(appid))
        return output
    finally:
        client.close()


def _duid(ip):
    """Return available DUID fields. Their output varies by Tizen version."""
    client = Sdb()
    try:
        client.connect(ip)
        output = client.shell('0 getduid')
        capability = client.capability()
        lines = [line for line in capability.splitlines() if 'duid' in line.lower() or 'sdk_toolpath' in line.lower()]
        return output + ('\n' if output and lines else '') + '\n'.join(lines)
    finally:
        client.close()


def main():
    """Run the small command-line subset used by the Tizen install flow."""
    args = sys.argv[1:]
    usage = ('Usage:\n'
             '  python3 tools/sdb.py connect <ip>\n'
             '  python3 tools/sdb.py devices\n'
             '  python3 tools/sdb.py shell <ip> <command...>\n'
             '  python3 tools/sdb.py push <ip> <local> <remote>\n'
             '  python3 tools/sdb.py capability <ip>\n'
             '  python3 tools/sdb.py duid <ip>')
    if not args:
        print(usage, file=sys.stderr)
        return 2
    try:
        if args[0] == 'devices' and len(args) == 1:
            print('List of devices attached')
            if LAST_DEVICE:
                print('%s:%d\tdevice\t%s' % (LAST_DEVICE[0], PORT, LAST_DEVICE[1]))
            return 0
        if args[0] == 'connect' and len(args) == 2:
            client = Sdb()
            try:
                banner = client.connect(args[1])
                print('%s:%d\tdevice\t%s' % (args[1], PORT, banner))
            finally:
                client.close()
            return 0
        if args[0] == 'shell' and len(args) >= 3:
            client = Sdb()
            try:
                client.connect(args[1])
                sys.stdout.write(client.shell(' '.join(args[2:])))
            finally:
                client.close()
            return 0
        if args[0] == 'push' and len(args) == 4:
            client = Sdb()
            try:
                client.connect(args[1])
                client.push(args[2], args[3])
            finally:
                client.close()
            print('%s: 1 file pushed' % args[2])
            return 0
        if args[0] == 'capability' and len(args) == 2:
            client = Sdb()
            try:
                client.connect(args[1])
                sys.stdout.write(client.capability())
            finally:
                client.close()
            return 0
        if args[0] == 'duid' and len(args) == 2:
            print(_duid(args[1]))
            return 0
    except SdbError as e:
        print('sdb: %s' % e, file=sys.stderr)
        return 1
    print(usage, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
