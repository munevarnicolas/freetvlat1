#!/usr/bin/env python3
"""Installs Free My TV on a Samsung TV in Developer Mode, over your Wi-Fi, with no Samsung tools and no Rosetta.

    python3 tizen/install.py 192.168.1.50              # push, install, then open it
    python3 tizen/install.py 192.168.1.50 --uninstall  # remove it

This drives the native SDB client in tools/sdb.py instead of Samsung's Intel-only `sdb` binary, so it runs on
Apple Silicon without Rosetta. Build the package first with the native signer:

    python3 tizen/build.py --sign-native                 # self-signed, for 2017-2022 TVs and testing
    python3 tizen/build.py --sign-native --author a.p12 --dist d.p12

Before the first install, on the TV: Apps, type 1 2 3 4 5 on the remote, turn Developer mode on, set Host PC IP to
this computer's IP, then restart the TV. 2017-2022 TVs install the self-signed package as it is. 2023+ TVs need a
Samsung device-unique certificate for the package (see CLAUDE.md); the SDB connection here needs none.

The push target and the installer command vary a little by Tizen version, so the exact `tools/sdb.py` install
helpers are documented from Samsung's commands and should be confirmed on the TV. `tools/sdb.py shell <ip> <cmd>`
lets you probe the TV directly if an install command differs on your firmware.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import sdb  # noqa: E402

WGT = os.path.join(ROOT, 'FreeMyTV-tizen.wgt')

with open(os.path.join(ROOT, 'tizen', 'config.xml'), encoding='utf-8') as f:
    _manifest = f.read()
APP_ID = re.search(r'<tizen:application id="([^"]+)"', _manifest).group(1)
PKG_ID = re.search(r'package="([^"]+)"', _manifest).group(1)


def install(ip):
    if not os.path.exists(WGT):
        sys.exit('Build it first: python3 tizen/build.py --sign-native (see this file\'s header).')
    print('Connecting to %s ...' % ip)
    print('Installing %s ...' % os.path.basename(WGT))
    out = sdb.install_wgt(ip, WGT, PKG_ID).strip()
    if out:
        print(out)
    print('Opening Free My TV ...')
    launched = sdb.launch_app(ip, APP_ID).strip()
    if launched:
        print(launched)
    print('Done. In the TV settings, turn on Autorun Last App (under Start Screen Options) so the TV starts into '
          'Free My TV.')


def uninstall(ip):
    print('Removing %s from %s ...' % (PKG_ID, ip))
    out = sdb.uninstall_pkg(ip, PKG_ID).strip()
    if out:
        print(out)
    print('Done.')


def main(args):
    if not args or args[0] in ('-h', '--help'):
        print(__doc__)
        return
    ip = args[0]
    try:
        if '--uninstall' in args:
            uninstall(ip)
        else:
            install(ip)
    except sdb.SdbError as e:
        sys.exit('sdb: %s' % e)


if __name__ == '__main__':
    main(sys.argv[1:])
