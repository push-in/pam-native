"""Distribution contract for the VS Code extension archive."""
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from xml.etree import ElementTree
from zipfile import ZipFile

from package import package


class PackageTest(unittest.TestCase):
    def test_installable_archive_is_deterministic_and_self_contained(self):
        with TemporaryDirectory() as directory:
            first = package(Path(directory) / 'first')
            second = package(Path(directory) / 'second')
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first.with_suffix('.vsix.sha256').read_text(),
                             hashlib.sha256(first.read_bytes()).hexdigest() + '  ' + first.name + '\n')
            with ZipFile(first) as archive:
                self.assertIsNone(archive.testzip())
                metadata = json.loads(archive.read('extension/package.json'))
                manifest = ElementTree.fromstring(archive.read('extension.vsixmanifest'))
                namespace = {'v': 'http://schemas.microsoft.com/developer/vsx-schema/2011'}
                identity = manifest.find('v:Metadata/v:Identity', namespace)
                self.assertEqual(identity.get('Version'), metadata['version'])
                self.assertEqual(identity.get('Publisher'), metadata['publisher'])
                self.assertEqual(identity.get('Id'), metadata['name'])
                for asset in manifest.findall('v:Assets/v:Asset', namespace):
                    self.assertIn(asset.get('Path'), archive.namelist())
                self.assertIn('extension/' + metadata['main'].removeprefix('./'), archive.namelist())
                for grammar in metadata['contributes']['grammars']:
                    self.assertIn('extension/' + grammar['path'].removeprefix('./'), archive.namelist())
                self.assertIn('Apache License', archive.read('extension/LICENSE.txt').decode())
                self.assertFalse(any('test' in name or name.endswith('.py') for name in archive.namelist()))
                ElementTree.fromstring(archive.read('[Content_Types].xml'))


if __name__ == '__main__':
    unittest.main()
