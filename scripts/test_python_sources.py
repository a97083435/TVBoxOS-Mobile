"""Python source isolation and network policy without third-party runtime dependencies."""
import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch, Mock


class PythonSourceTests(unittest.TestCase):
    def setUp(self):
        file = Path(__file__).resolve().parents[1] / 'engine/chaquo/src/main/python/app.py'
        spec = importlib.util.spec_from_file_location('runtime_source_fixture', file)
        self.app = importlib.util.module_from_spec(spec)
        self.requests = types.ModuleType('requests')
        self.requests.get = Mock()
        with patch.dict(sys.modules, {'requests': self.requests}):
            spec.loader.exec_module(self.app)

    def test_same_filename_sites_keep_independent_module_globals(self):
        def download(path, api):
            text = 'one' if 'first.example' in api else 'two'
            Path(path).write_text("site = %r\nclass Spider:\n def title(self): return site\n" % text)
        with tempfile.TemporaryDirectory() as directory, patch.object(self.app, 'download', download):
            first = self.app.spider(directory, 'https://first.example/spider.py?token=fixture')
            second = self.app.spider(directory, 'https://second.example/spider.py?token=fixture')
            self.assertEqual('one', first.title())
            self.assertEqual('two', second.title())
            self.assertNotEqual(type(first).__module__, type(second).__module__)
            self.assertEqual(2, len(list(Path(directory).glob('*.py'))))

    def test_same_script_with_two_extensions_has_independent_globals_and_cleanup(self):
        def download(path, api):
            Path(path).write_text("value = ''\nclass Spider:\n def init(self, ext):\n  global value\n  value=ext\n def title(self): return value\n def destroy(self): pass\n")
        with tempfile.TemporaryDirectory() as directory, patch.object(self.app, 'download', download):
            first = self.app.spider(directory, 'https://example.org/spider.py')
            second = self.app.spider(directory, 'https://example.org/spider.py')
            first.init('one')
            second.init('two')
            self.assertEqual('one', first.title())
            self.assertEqual('two', second.title())
            name = type(first).__module__
            self.app.destroy(first)
            self.assertNotIn(name, sys.modules)
            self.assertEqual('two', second.title())
            self.app.destroy(second)

    def test_http_download_has_timeout_redirect_limit_and_status_validation(self):
        response = self.requests.get.return_value
        self.assertIs(response, self.app.redirect('https://example.org/source.py'))
        self.requests.get.assert_called_once_with('https://example.org/source.py', allow_redirects=True,
                                                verify=False, timeout=(10, 30))
        response.raise_for_status.assert_called_once_with()

    def test_http_failure_does_not_become_source_code(self):
        self.requests.get.return_value.raise_for_status.side_effect = IOError('HTTP 403')
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'source.py'
            with self.assertRaises(IOError):
                self.app.download(str(target), 'https://example.org/source.py')
            self.assertFalse(target.exists())


if __name__ == '__main__':
    unittest.main()
