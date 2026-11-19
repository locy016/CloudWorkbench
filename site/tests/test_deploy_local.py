import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy_local', Path(__file__).resolve().parents[1] / 'deploy_local.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.dist, self.live, self.backup = root / 'dist', root / 'live', root / 'backup'
        for directory in [self.dist / 'assets', self.live / 'assets']:
            directory.mkdir(parents=True)
        (self.live / 'index.html').write_text('old entry')
        (self.live / 'assets/old.js').write_text('old client asset')
        (self.dist / 'index.html').write_text('<script src="/assets/new.js"></script>')
        (self.dist / 'assets/new.js').write_text('new client asset')

    def test_backup_preservation_and_entry_last(self):
        replaced = []
        original = Path.replace

        def track(path, target):
            replaced.append(Path(target).name)
            return original(path, target)

        with patch.object(Path, 'replace', track):
            self.assertEqual(deploy.publish(self.dist, self.live, self.backup), 2)
        self.assertEqual(replaced[-1], 'index.html')
        self.assertEqual((self.backup / 'index.html').read_text(), 'old entry')
        self.assertEqual((self.live / 'assets/old.js').read_text(), 'old client asset')
        self.assertEqual((self.live / 'assets/new.js').read_text(), 'new client asset')
        self.assertEqual(list(self.live.rglob('.qiwuli-*')), [])

    def test_missing_build_asset_does_not_touch_live(self):
        (self.dist / 'assets/new.js').unlink()
        with self.assertRaises(ValueError):
            deploy.publish(self.dist, self.live, self.backup)
        self.assertEqual((self.live / 'index.html').read_text(), 'old entry')
        self.assertFalse(self.backup.exists())

    def test_symlink_destinations_and_build_files_are_rejected(self):
        (self.live / 'assets/link.js').symlink_to(self.dist / 'assets/new.js')
        with self.assertRaises(ValueError):
            deploy.publish(self.dist, self.live, self.backup)
        (self.live / 'assets/link.js').unlink()
        (self.dist / 'link.js').symlink_to(self.live / 'assets/old.js')
        with self.assertRaises(ValueError):
            deploy.check_build(self.dist)


if __name__ == '__main__':
    unittest.main()
