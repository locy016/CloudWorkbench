import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'ops/release-service/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseServiceTest(unittest.TestCase):
    def test_unsigned_archive_must_match_source_and_checksum(self):
        source = '8' * 40
        data = b'example unsigned apk'
        checksum = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            with zipfile.ZipFile(path / 'release.zip', 'w') as artifact:
                artifact.writestr(release.APK, data)
                artifact.writestr('SHA256SUMS', f'{checksum}  {release.APK}\n')
                artifact.writestr('source-commit.txt', source + '\n')
            with self.assertRaisesRegex(release.Rejected, '源码提交'):
                release.extract(path / 'release.zip', path, 'f' * 40)
            self.assertEqual(release.extract(path / 'release.zip', path, source), checksum)

    def test_missing_or_mismatched_approval_does_not_invoke_signer(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            with patch.object(release, 'APPROVALS', path / 'approvals'), patch.object(release, 'REQUESTS', path), patch.object(release, 'invoke') as invoke:
                release.publish('1', Path('/unused'))
                (path / 'approvals').mkdir()
                (path / '1').mkdir()
                (path / '1/candidate.json').write_text(json.dumps({'approvalSha256': 'a' * 64}))
                (path / 'approvals/1.json').write_text(json.dumps({'sha256': 'b' * 64}))
                with self.assertRaisesRegex(release.Rejected, '批准摘要'):
                    release.publish('1', Path('/unused'))
                invoke.assert_not_called()

    def test_failed_run_cannot_be_submitted(self):
        with patch.object(release, 'github_json', return_value={'id': 1, 'repository': {'full_name': release.REPO},
                'event': 'workflow_dispatch', 'head_branch': 'main', 'path': release.WORKFLOW,
                'conclusion': 'failure', 'display_title': '云端工作台 CI · ' + 'a' * 40}):
            with self.assertRaisesRegex(release.Rejected, '已通过'):
                release.provenance('1')

    def test_publisher_stages_signing_output_on_update_mount(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            approvals, requests, updates = (path / name for name in ('approvals', 'requests', 'updates'))
            for folder in (approvals, requests / '1', updates):
                folder.mkdir(parents=True)
            apk = b'unsigned input'
            signed = b'validated signed output'
            data = {'approvalSha256': 'a' * 64, 'apkSha256': hashlib.sha256(apk).hexdigest(),
                    'versionCode': 85, 'versionName': '0.4.72', 'notes': 'update'}
            (approvals / '1.json').write_text(json.dumps({'sha256': data['approvalSha256']}))
            (requests / '1/candidate.json').write_text(json.dumps(data))
            (requests / '1' / release.APK).write_bytes(apk)
            (requests / '1/signed.apk').write_bytes(signed)
            (updates / 'current.json').write_text(json.dumps({'versionCode': 84}))

            def publisher(*args, **kwargs):
                manifest, package = map(Path, args[4:6])
                self.assertEqual(manifest.parent, package.parent)
                self.assertEqual(manifest.parent.parent, updates)
                self.assertEqual(package.read_bytes(), signed)
                self.assertEqual(json.loads(manifest.read_text())['versionCode'], 85)

            with patch.multiple(release, APPROVALS=approvals, REQUESTS=requests, UPDATES=updates), \
                    patch.object(release, 'receipt') as receipt, patch.object(release, 'checked_signer'), \
                    patch.object(release, 'apk_version', return_value=(85, '0.4.72')), \
                    patch.object(release, 'invoke', side_effect=publisher) as invoke:
                release.publish('1', path / 'unused')
                invoke.assert_called_once()
                self.assertEqual(receipt.call_args.args, ('1', 'published'))


if __name__ == '__main__':
    unittest.main()
