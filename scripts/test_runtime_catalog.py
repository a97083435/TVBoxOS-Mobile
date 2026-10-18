import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('runtime_catalog', Path(__file__).with_name('runtime_catalog.py'))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class RuntimeCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.host = self.root / 'host.apk'
        self.host.write_bytes(b'signed-host-fixture')
        for name in ['python', 'quickjs', 'thunder', 'nodejs', 'bdj']:
            apk = self.root / f'runtime/{name}/build/outputs/apk/release/runtime-{name}-release.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(('signed-' + name).encode())
            code, version = (3, '1.0.2') if name in ('python', 'quickjs') else (2, '1.0.1')
            (self.root / f'runtime/{name}/build.gradle').write_text(f"versionCode {code}\nversionName '{version}'\n")
        self.out = self.root / 'dist'
        self.args = ['runtime_catalog.py', '--variant', 'release', '--base-url', 'https://example.org/releases/download/v3.0.5/',
                     '--output', str(self.out), '--host-apk', str(self.host), '--aapt', 'aapt', '--apksigner', 'apksigner']

    def output(self, command, **kwargs):
        if command[0] == 'apksigner':
            return 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64
        name = Path(command[-1]).parents[4].name
        code, version = (3, '1.0.2') if name in ('python', 'quickjs') else (2, '1.0.1')
        return f"package: name='com.kukuqi.tvbox.runtime.{name}' versionCode='{code}' versionName='{version}'\n"

    def run_catalog(self, output=None):
        with patch.object(catalog, '__file__', str(self.root / 'scripts/runtime_catalog.py')), patch.object(sys, 'argv', self.args), \
                patch.object(catalog.subprocess, 'check_output', side_effect=output or self.output):
            catalog.main()

    def test_pinned_download_urls_and_measured_checksums(self):
        self.run_catalog()
        result = json.loads((self.out / 'catalog.json').read_text())
        self.assertEqual(5, len(result['modules']))
        for module in result['modules']:
            code, version = (3, '1.0.2') if module['id'] in ('python', 'quickjs') else (2, '1.0.1')
            self.assertEqual(code, module['versionCode'])
            self.assertEqual(version, module['versionName'])
            artifact = module['artifacts'][0]
            data = (self.out / artifact['url'].rsplit('/', 1)[-1]).read_bytes()
            self.assertIn('/v3.0.5/', artifact['url'])
            self.assertEqual(hashlib.sha256(data).hexdigest(), artifact['sha256'])
            self.assertEqual(len(data), artifact['size'])

    def test_shared_native_declarations_are_used_for_real_catalog(self):
        (self.root / 'runtime/native.gradle').write_text("versionCode 2\nversionName '1.0.1'\n")
        for name in ('nodejs', 'bdj'):
            (self.root / f'runtime/{name}/build.gradle').write_text("apply from: rootProject.file('runtime/native.gradle')\n")
        self.run_catalog()
        result = json.loads((self.out / 'catalog.json').read_text())
        self.assertEqual({'python', 'quickjs', 'thunder', 'nodejs', 'bdj'}, {m['id'] for m in result['modules']})

    def test_all_checkout_module_versions_can_be_resolved(self):
        checkout = Path(__file__).resolve().parents[1]
        for name in ('python', 'quickjs', 'thunder', 'nodejs', 'bdj'):
            code, version = catalog.module_version(checkout, name)
            self.assertGreater(code, 0)
            self.assertRegex(version, r'^\d+\.\d+\.\d+$')

    def test_stale_module_version_cannot_be_published(self):
        def stale(command, **kwargs):
            result = self.output(command, **kwargs)
            return result.replace("versionCode='3' versionName='1.0.2'", "versionCode='2' versionName='1.0.1'")
        with self.assertRaises(SystemExit):
            self.run_catalog(stale)
        self.assertFalse((self.out / 'catalog.json').exists())

    def test_foreign_module_certificate_blocks_catalog(self):
        def foreign(command, **kwargs):
            value = self.output(command, **kwargs)
            return value.replace('a' * 64, 'b' * 64) if command[0] == 'apksigner' and command[-1] != str(self.host) else value
        with self.assertRaises(SystemExit):
            self.run_catalog(foreign)
        self.assertFalse((self.out / 'catalog.json').exists())

    def test_debug_or_foreign_package_cannot_be_published(self):
        for suffix in ['application-debuggable\n', '']:
            def invalid(command, **kwargs):
                value = self.output(command, **kwargs)
                if command[0] == 'aapt':
                    return value + suffix if suffix else value.replace('com.kukuqi', 'com.other')
                return value
            with self.assertRaises(SystemExit):
                self.run_catalog(invalid)
            self.assertFalse((self.out / 'catalog.json').exists())


if __name__ == '__main__':
    unittest.main()
