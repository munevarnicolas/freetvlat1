import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    'webos_build', Path(__file__).resolve().parents[1] / 'build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class AppInventoryBuildTests(unittest.TestCase):
    def test_inventory_retains_only_app_metadata(self):
        apps = build.validate_apps([{
            'id': 'netflix', 'title': 'Netflix', 'iconColor': '#E50914',
            'client-key': 'sensitive-pairing-token',
            'icon': 'http://private-tv-address/icon.png',
            'ip': 'private-tv-address',
        }])
        self.assertEqual(apps, [{'id': 'netflix', 'title': 'Netflix', 'color': '#E50914'}])

    def test_inventory_rejects_invalid_shape_and_ids(self):
        for raw in ({'apps': []}, [None], [{'id': '../private'}]):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                build.validate_apps(raw)

    def test_generic_build_does_not_read_personal_inventory(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root) / 'webos'
            directory.mkdir()
            (directory / 'apps.local.json').write_text(
                json.dumps([{'id': 'netflix', 'title': 'Netflix'}]), encoding='utf-8')
            with patch.object(build, 'ROOT', root):
                self.assertEqual(build.apps_from_args(['--generic']), [])
                self.assertEqual(build.apps_from_args([])[0]['id'], 'netflix')

    def test_explicit_inventory_path_is_validated(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'apps.json'
            path.write_text('[{"id":"amazon","title":"Prime Video"}]', encoding='utf-8')
            self.assertEqual(build.apps_from_args(['--apps', str(path)]), [
                {'id': 'amazon', 'title': 'Prime Video', 'color': ''}])
            path.write_text('{"unexpected":true}', encoding='utf-8')
            with self.assertRaises(ValueError):
                build.apps_from_args(['--apps', str(path)])

    def test_generic_build_rejects_explicit_personal_inventory(self):
        for args in (['--generic', '--apps', 'apps.json'],
                     ['--apps', 'apps.json', '--generic']):
            with self.subTest(args=args), self.assertRaisesRegex(ValueError, 'cannot be combined'):
                build.apps_from_args(args)


if __name__ == '__main__':
    unittest.main()
