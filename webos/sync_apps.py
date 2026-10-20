#!/usr/bin/env python3
"""Collect an LG TV's installed apps on the PC, after approval on the TV.

Requires the optional websocket-client package. Pairing keys and the TV address
are kept in memory only; the output contains only validated app metadata.
"""
import argparse
import getpass
import ipaddress
import json
import os
from pathlib import Path
import ssl
import sys
import tempfile
import time


class SyncError(Exception):
    """A safe message that contains no connection details or TV payloads."""


def receive(websocket, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise SyncError('The TV did not respond in time. Try again and accept the TV prompt.')
    websocket.settimeout(remaining)
    message = json.loads(websocket.recv())
    if not isinstance(message, dict):
        raise SyncError('The TV returned an invalid response.')
    if message.get('type') == 'error':
        raise SyncError('The TV refused the request. Accept pairing on the TV and try again.')
    return message


def collect_apps(host, connect=None):
    """Pair for this connection only, then return sanitized installed apps."""
    try:
        address = ipaddress.ip_address(host)
        if '%' in host:
            raise ValueError()
    except ValueError:
        raise SyncError('Enter a valid TV IP address.') from None
    if connect is None:
        try:
            from websocket import create_connection
        except ImportError:
            raise SyncError('Install the optional dependency: python -m pip install websocket-client') from None
        connect = create_connection
    websocket = None
    try:
        target = '[%s]' % address if address.version == 6 else str(address)
        # LG's local SSAP endpoint uses a self-signed certificate. This exception
        # applies only to the literal TV IP entered for this local connection.
        websocket = connect('wss://%s:3001' % target, suppress_origin=True,
                            sslopt={'cert_reqs': ssl.CERT_NONE}, timeout=5)
        websocket.send(json.dumps({
            'id': 'register', 'type': 'register', 'payload': {
                'pairingType': 'PROMPT',
                'manifest': {
                    'manifestVersion': 1, 'appVersion': '1.0',
                    'localizedAppNames': {'': 'Free My TV Setup'},
                    'permissions': ['READ_INSTALLED_APPS']
                }
            }
        }))
        deadline = time.monotonic() + 120
        while True:
            message = receive(websocket, deadline)
            if message.get('type') == 'registered':
                break
            if message.get('id') == 'register' and message.get('payload', {}).get('returnValue') is False:
                raise SyncError('The TV refused pairing. Accept the TV prompt and try again.')
        deadline = time.monotonic() + 15
        websocket.settimeout(15)
        websocket.send(json.dumps({'id': 'apps', 'type': 'request',
                                  'uri': 'ssap://com.webos.applicationManager/listApps', 'payload': {}}))
        while True:
            message = receive(websocket, deadline)
            if message.get('id') != 'apps':
                continue
            payload = message.get('payload')
            if not isinstance(payload, dict) or payload.get('returnValue') is False:
                raise SyncError('The TV could not list installed apps.')
            from build import validate_apps
            apps = validate_apps(payload.get('apps'))
            if not apps:
                raise SyncError('The TV returned no apps. The previous inventory was preserved.')
            return apps
    except SyncError:
        raise
    except Exception:
        # Transport and validation exceptions can include addresses or raw data.
        raise SyncError('Could not collect apps. Check the connection and accept pairing on the TV.') from None
    finally:
        if websocket is not None:
            try:
                websocket.close()
            except Exception:
                pass


def save_apps(apps, output):
    """Replace the inventory atomically only after a successful collection."""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8',
                                         dir=output.parent, delete=False) as stream:
            temporary = stream.name
            json.dump(apps, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(temporary, output)
    finally:
        if temporary is not None and os.path.exists(temporary):
            os.unlink(temporary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).with_name('apps.local.json'))
    args = parser.parse_args(argv)
    try:
        host = getpass.getpass('TV IP address (hidden; not saved): ').strip()
        print('Accept the Free My TV Setup pairing prompt on the TV.')
        apps = collect_apps(host)
        save_apps(apps, args.output)
    except (KeyboardInterrupt, EOFError):
        print('App collection cancelled. The previous inventory was preserved.', file=sys.stderr)
        return 1
    except SyncError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        print('Could not save the app inventory. The previous inventory was preserved.', file=sys.stderr)
        return 1
    print('Saved %d installed apps. Build again to include the inventory.' % len(apps))
    return 0


if __name__ == '__main__':
    sys.exit(main())
