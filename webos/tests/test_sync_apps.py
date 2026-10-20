"""Offline checks for the PC app inventory collector."""
import contextlib
import io
import json
from pathlib import Path
import ssl
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sync_apps


APP = {'id': 'com.example.video', 'title': 'Video', 'color': '#123456'}
HOST = '192.0.2.7'
SECRET = 'PAIRING-SECRET'


class Transport:
    def __init__(self, messages):
        self.messages = iter(messages)
        self.sent = []
        self.timeouts = []
        self.closed = False

    def send(self, message):
        self.sent.append(json.loads(message))

    def recv(self):
        value = next(self.messages)
        if isinstance(value, Exception):
            raise value
        return json.dumps(value)

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def close(self):
        self.closed = True


def success_messages(apps=None):
    return [
        {'id': 'register', 'type': 'response', 'payload': {'pairingType': 'PROMPT'}},
        {'id': 'register', 'type': 'registered', 'payload': {'client-key': SECRET}},
        {'id': 'apps', 'type': 'response', 'payload': {
            'returnValue': True, 'apps': apps if apps is not None else [dict(APP, host=HOST, **{'client-key': SECRET})]
        }}
    ]


class SyncAppsTests(unittest.TestCase):
    def test_minimal_pairing_and_sanitized_inventory(self):
        transport = Transport(success_messages())
        connect = unittest.mock.Mock(return_value=transport)
        self.assertEqual(sync_apps.collect_apps(HOST, connect), [APP])
        connect.assert_called_once_with('wss://192.0.2.7:3001', suppress_origin=True,
                                        sslopt={'cert_reqs': ssl.CERT_NONE}, timeout=5)
        registration = transport.sent[0]
        self.assertEqual(registration['payload']['manifest']['permissions'], ['READ_INSTALLED_APPS'])
        self.assertEqual(registration['payload']['pairingType'], 'PROMPT')
        self.assertNotIn('client-key', registration['payload'])
        self.assertEqual(transport.sent[1]['uri'], 'ssap://com.webos.applicationManager/listApps')
        self.assertTrue(transport.closed)
        self.assertTrue(all(0 < timeout <= 120 for timeout in transport.timeouts))
        self.assertLessEqual(transport.timeouts[-1], 15)

    def test_ipv6_literal_uses_brackets(self):
        connect = unittest.mock.Mock(return_value=Transport(success_messages()))
        sync_apps.collect_apps('2001:db8::7', connect)
        self.assertEqual(connect.call_args.args[0], 'wss://[2001:db8::7]:3001')

    def test_rejects_nonliteral_addresses_before_connecting(self):
        for host in ('tv.example.test', 'https://192.0.2.7', 'fe80::1%adapter'):
            with self.subTest(host=host):
                connect = unittest.mock.Mock()
                with self.assertRaises(sync_apps.SyncError):
                    sync_apps.collect_apps(host, connect)
                connect.assert_not_called()

    def test_optional_dependency_has_actionable_safe_error(self):
        with patch.dict(sys.modules, {'websocket': None}):
            with self.assertRaisesRegex(sync_apps.SyncError, 'pip install websocket-client'):
                sync_apps.collect_apps(HOST)

    def test_errors_close_transport_and_do_not_expose_connection_data(self):
        cases = [
            [{'type': 'error', 'error': HOST + SECRET}],
            [{'id': 'register', 'payload': {'returnValue': False}}],
            [RuntimeError('wss://' + HOST + SECRET)],
            success_messages([]),
            success_messages([{'id': '../invalid', 'title': SECRET}]),
            [[], ],
        ]
        for messages in cases:
            with self.subTest(messages=messages):
                transport = Transport(messages)
                with self.assertRaises(sync_apps.SyncError) as error:
                    sync_apps.collect_apps(HOST, lambda *args, **kwargs: transport)
                self.assertNotIn(HOST, str(error.exception))
                self.assertNotIn(SECRET, str(error.exception))
                self.assertTrue(transport.closed)

    def test_pairing_deadline_is_finite(self):
        transport = Transport([])
        with patch.object(sync_apps.time, 'monotonic', side_effect=[0, 121]):
            with self.assertRaisesRegex(sync_apps.SyncError, 'in time'):
                sync_apps.collect_apps(HOST, lambda *args, **kwargs: transport)
        self.assertTrue(transport.closed)

    def run_cli(self, output, transport):
        collect = sync_apps.collect_apps
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sync_apps.getpass, 'getpass', return_value=HOST), \
                patch.object(sync_apps, 'collect_apps', side_effect=lambda host: collect(
                    host, lambda *args, **kwargs: transport)), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = sync_apps.main(['--output', str(output)])
        return result, stdout.getvalue() + stderr.getvalue()

    def test_cli_only_persists_metadata(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
            output = Path(directory) / 'apps.json'
            result, messages = self.run_cli(output, Transport(success_messages()))
            self.assertEqual(result, 0)
            self.assertEqual(json.loads(output.read_text(encoding='utf-8')), [APP])
            self.assertNotIn(HOST, output.read_text(encoding='utf-8') + messages)
            self.assertNotIn(SECRET, output.read_text(encoding='utf-8') + messages)
            self.assertEqual(list(Path(directory).iterdir()), [output])

    def test_cli_failure_preserves_previous_inventory(self):
        for responses in ([{'type': 'error', 'error': HOST + SECRET}], success_messages([])):
            with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
                output = Path(directory) / 'apps.json'
                output.write_text('previous inventory', encoding='utf-8')
                result, messages = self.run_cli(output, Transport(responses))
                self.assertEqual(result, 1)
                self.assertEqual(output.read_text(encoding='utf-8'), 'previous inventory')
                self.assertNotIn(HOST, messages)
                self.assertNotIn(SECRET, messages)

    def test_failed_atomic_replace_preserves_previous_inventory(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
            output = Path(directory) / 'apps.json'
            output.write_text('previous inventory', encoding='utf-8')
            with patch.object(sync_apps.os, 'replace', side_effect=OSError(HOST + SECRET)):
                result, messages = self.run_cli(output, Transport(success_messages()))
            self.assertEqual(result, 1)
            self.assertEqual(output.read_text(encoding='utf-8'), 'previous inventory')
            self.assertEqual(list(Path(directory).iterdir()), [output])
            self.assertNotIn(HOST, messages)
            self.assertNotIn(SECRET, messages)


if __name__ == '__main__':
    unittest.main()
