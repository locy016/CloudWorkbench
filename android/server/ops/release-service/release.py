#!/usr/bin/python3
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


REPO = 'locy016/CloudWorkbench'
API = f'https://api.github.com/repos/{REPO}'
WORKFLOW = '.github/workflows/cloud-workbench-ci.yml'
PACKAGE = 'cn.xux.cloudworkbench'
CERTIFICATE = 'b5aaa0f7308677f16157d37260884e4d51b2cd52edc2d373722ff52be126014a'
ROOT = Path('/var/lib/cloud-workbench-release')
INBOX = Path('/var/spool/cloud-workbench-release/inbox')
REQUESTS = ROOT / 'requests'
APPROVALS = ROOT / 'approvals'
RECEIPTS = ROOT / 'receipts'
UPDATES = ROOT / 'updates'
PUBLISHER = Path('/usr/local/lib/cloud-workbench-release/ops/publish-app-update.mjs')
LIMIT = 384 * 1024 * 1024
APK = 'app-release-unsigned.apk'
SHA = re.compile(r'[0-9a-f]{64}')
RUN = re.compile(r'[1-9][0-9]{0,17}')


class Rejected(ValueError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def github(path, token=None):
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'CloudWorkbenchRelease',
               'X-GitHub-Api-Version': '2026-03-10'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(API + path, headers=headers)
    return urllib.request.build_opener(NoRedirect).open(request, timeout=20)


def github_json(path):
    with github(path) as response:
        return json.load(response)


def provenance(run_id):
    run = github_json('/actions/runs/' + run_id)
    title = re.fullmatch(r'云端工作台 CI · ([0-9a-f]{40})(?: · [0-9a-f-]{36})?', run.get('display_title', ''))
    if (run.get('id') != int(run_id) or run.get('repository', {}).get('full_name') != REPO or
            run.get('path', '').split('@')[0] != WORKFLOW or run.get('head_branch') != 'main' or
            run.get('event') != 'workflow_dispatch' or run.get('conclusion') != 'success' or not title):
        raise Rejected('请提交来自 main 工作流且已通过的构建')
    source = title[1]
    comparison = github_json(f'/compare/{source}...main?per_page=1')
    if comparison.get('status') not in ('ahead', 'identical') or comparison.get('base_commit', {}).get('sha') != source:
        raise Rejected('构建源码未纳入 main')
    artifacts = github_json(f'/actions/runs/{run_id}/artifacts?per_page=20').get('artifacts', [])
    eligible = [item for item in artifacts if item.get('name') == 'android-unsigned-apk' and not item.get('expired')]
    if len(eligible) != 1:
        raise Rejected('构建缺少可用的未签名 APK 产物')
    artifact = eligible[0]
    if not SHA.fullmatch(artifact.get('digest', '').removeprefix('sha256:')):
        raise Rejected('GitHub 未返回有效的产物摘要')
    return source, artifact


def copy_bytes(source, target, limit):
    digest = hashlib.sha256()
    received = 0
    while block := source.read(1024 * 1024):
        received += len(block)
        if received > limit:
            raise Rejected('候选文件超出允许大小')
        digest.update(block)
        if target is not None:
            target.write(block)
    return digest.hexdigest()


def hash_file(path):
    with path.open('rb') as source:
        return copy_bytes(source, None, LIMIT)


def safe_open(path, limit, directory=None):
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=directory)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise Rejected('候选文件格式或大小无效')
        return os.fdopen(fd, 'rb')
    except BaseException:
        os.close(fd)
        raise


def atomic_json(path, value, mode=0o600, group=None):
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as output:
            if group is not None:
                os.fchown(output.fileno(), -1, group)
            os.fchmod(output.fileno(), mode)
            json.dump(value, output, ensure_ascii=False, sort_keys=True)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def submit(run_id, notes):
    if not 0 < len(notes) <= 2000:
        raise Rejected('请填写 1 至 2000 字的更新说明')
    _, artifact = provenance(run_id)
    token = (Path.home() / '.local/share/cloud-workbench/github-actions.token').read_text().strip()
    try:
        with github(f"/actions/artifacts/{artifact['id']}/zip", token):
            raise Rejected('GitHub 未返回预期的下载地址')
    except urllib.error.HTTPError as error:
        if error.code != 302:
            raise
        location = urllib.parse.urlsplit(error.headers['Location'])
        error.close()
    if (location.scheme != 'https' or location.username or location.password or location.port or
            not re.fullmatch(r'productionresultssa[0-9]+\.blob\.core\.windows\.net', location.hostname or '')):
        raise Rejected('GitHub 产物下载地址无效')
    drop = INBOX / run_id
    drop.mkdir(mode=0o770)
    os.chmod(drop, 0o2770)
    try:
        # The storage host receives a signed URL and no GitHub token.
        stream = urllib.request.build_opener(NoRedirect).open(location.geturl(), timeout=60)
        with stream, (drop / 'artifact.zip').open('xb') as target:
            digest = copy_bytes(stream, target, LIMIT)
            os.fchmod(target.fileno(), 0o660)
        if digest != artifact['digest'].removeprefix('sha256:'):
            raise Rejected('下载产物与 GitHub 摘要不一致')
        atomic_json(drop / 'ready.json', {'notes': notes}, mode=0o660)
    except BaseException:
        shutil.rmtree(drop)
        raise
    print(f'已提交构建 {run_id}，请等待校验并查看状态。')


def extract(archive, directory, source):
    with zipfile.ZipFile(archive) as package_zip:
        names = package_zip.namelist()
        if sorted(names) != sorted([APK, 'SHA256SUMS', 'source-commit.txt']):
            raise Rejected('GitHub 产物文件列表不匹配')
        if any(package_zip.getinfo(item).file_size > 256 for item in ('SHA256SUMS', 'source-commit.txt')):
            raise Rejected('构建摘要格式无效')
        if package_zip.read('source-commit.txt').decode('ascii').strip() != source:
            raise Rejected('APK 源码提交与运行记录不一致')
        checksum = re.fullmatch(r'([0-9a-f]{64})  app-release-unsigned\.apk',
                                package_zip.read('SHA256SUMS').decode('ascii').strip())
        if not checksum:
            raise Rejected('未签名 APK 摘要无效')
        with package_zip.open(APK) as apk, (directory / APK).open('xb') as target:
            digest = copy_bytes(apk, target, LIMIT)
        if digest != checksum[1]:
            raise Rejected('未签名 APK 与构建摘要不一致')
        return digest


def invoke(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=180, check=False)
    if result.returncode:
        raise Rejected(f'{Path(args[0]).name} 校验或处理失败')
    return result.stdout


def apk_version(path):
    output = invoke('/usr/bin/aapt', 'dump', 'badging', str(path))
    match = re.search(r"^package: name='cn\.xux\.cloudworkbench' versionCode='([1-9][0-9]{0,9})' "
                      r"versionName='([0-9A-Za-z][0-9A-Za-z.+-]{0,39})'", output, re.MULTILINE)
    if not match or int(match[1]) > 2100000000:
        raise Rejected('APK 包名或版本无效')
    return int(match[1]), match[2]


def checked_signer(path):
    output = invoke('/usr/bin/apksigner', 'verify', '--print-certs', str(path))
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.MULTILINE)
    if [item.lower() for item in signers] != [CERTIFICATE]:
        raise Rejected('APK 证书与当前正式客户端不一致')


def receipt(run_id, state, **fields):
    atomic_json(RECEIPTS / (run_id + '.json'), {'runId': run_id, 'state': state, **fields}, 0o640)


def accept(run_id):
    incoming = INBOX / run_id
    candidate = REQUESTS / run_id
    if candidate.is_dir() and not (RECEIPTS / (run_id + '.json')).is_file():
        receipt(run_id, 'awaiting-approval', candidate=json.loads((candidate / 'candidate.json').read_text()))
    # Both files untrusted until copied and matched to the independently fetched GitHub digest.
    if not (incoming / 'ready.json').is_file() or candidate.exists():
        return
    directory = os.open(incoming, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    staged = Path(tempfile.mkdtemp(dir=REQUESTS))
    try:
        with safe_open('ready.json', 16 * 1024, directory) as ready:
            record = json.load(ready)
        notes = record.get('notes') if isinstance(record, dict) else None
        if not isinstance(notes, str) or not 0 < len(notes) <= 2000:
            raise Rejected('更新说明无效')
        source, artifact = provenance(run_id)
        archive = staged / 'archive.zip'
        with safe_open('artifact.zip', LIMIT, directory) as offered, archive.open('xb') as stored:
            digest = copy_bytes(offered, stored, LIMIT)
        if digest != artifact['digest'].removeprefix('sha256:'):
            raise Rejected('候选产物与 GitHub 摘要不一致')
        apk_sha = extract(archive, staged, source)
        archive.unlink()
        code, version = apk_version(staged / APK)
        if code <= json.loads((UPDATES / 'current.json').read_text())['versionCode']:
            raise Rejected('候选构建号必须高于私有更新源')
        status = {'runId': run_id, 'source': source, 'artifactId': artifact['id'], 'apkSha256': apk_sha,
                  'versionCode': code, 'versionName': version, 'notes': notes}
        status['approvalSha256'] = hashlib.sha256(json.dumps(status, sort_keys=True).encode()).hexdigest()
        atomic_json(staged / 'candidate.json', status)
        staged.rename(candidate)
        receipt(run_id, 'awaiting-approval', candidate=status)
        for name in ('ready.json', 'artifact.zip'):
            os.unlink(name, dir_fd=directory)
    finally:
        os.close(directory)
        if staged.exists():
            shutil.rmtree(staged)
    incoming.rmdir()


def approve(run_id, expected):
    if os.geteuid() != 0:
        raise PermissionError('Approval requires a separate administrator session')
    data = json.loads((REQUESTS / run_id / 'candidate.json').read_text())
    if expected != data['approvalSha256']:
        raise Rejected('批准摘要不匹配')
    path = APPROVALS / (run_id + '.json')
    if not path.exists():
        atomic_json(path, {'sha256': expected, 'approvedAt': int(time.time())}, 0o640, APPROVALS.stat().st_gid)
    print(f'已批准构建 {run_id} {expected}')


def publish(run_id, credentials):
    directory = REQUESTS / run_id
    approval = APPROVALS / (run_id + '.json')
    if not approval.is_file():
        return
    data = json.loads((directory / 'candidate.json').read_text())
    if json.loads(approval.read_text())['sha256'] != data['approvalSha256']:
        raise Rejected('管理员批准摘要与候选包不一致')
    current = json.loads((UPDATES / 'current.json').read_text())
    signed = directory / 'signed.apk'
    def complete(update):
        receipt(run_id, 'published', candidate=data, update=update)
        for name in (APK, 'signed.apk', 'manifest.json'):
            (directory / name).unlink(missing_ok=True)

    if current['versionCode'] == data['versionCode'] and signed.is_file() and hash_file(signed) == current['sha256']:
        complete(current)
        return
    if current['versionCode'] >= data['versionCode']:
        raise Rejected('候选构建号已被同级或更新版本占用')
    if hash_file(directory / APK) != data['apkSha256']:
        raise Rejected('批准后候选包发生变化')
    if not signed.is_file():
        staging = directory / 'signing.apk'
        staging.unlink(missing_ok=True)
        invoke('/usr/bin/apksigner', 'sign', '--ks', str(credentials / 'release.p12'), '--ks-type', 'PKCS12',
               '--ks-key-alias', 'cloud-workbench-release', '--ks-pass', 'file:' + str(credentials / 'password'),
               '--v4-signing-enabled', 'false', '--out', str(staging), str(directory / APK))
        checked_signer(staging)
        staging.rename(signed)
    checked_signer(signed)
    if apk_version(signed) != (data['versionCode'], data['versionName']):
        raise Rejected('签名后 APK 版本不匹配')
    update = {key: data[key] for key in ('versionCode', 'versionName', 'notes')}
    update.update(packageId=PACKAGE, certificate=CERTIFICATE, sha256=hash_file(signed), size=signed.stat().st_size)
    manifest = directory / 'manifest.json'
    atomic_json(manifest, update)
    invoke('/usr/bin/flock', str(UPDATES / '.publish.lock'), '/usr/local/bin/node', str(PUBLISHER),
           str(manifest), str(signed), str(UPDATES), '--group-readable')
    complete(update)


def work():
    credentials = Path(os.environ['CREDENTIALS_DIRECTORY'])
    for incoming in sorted(INBOX.iterdir()):
        if RUN.fullmatch(incoming.name):
            try:
                accept(incoming.name)
            except (Rejected, UnicodeError, zipfile.BadZipFile, json.JSONDecodeError) as error:
                receipt(incoming.name, 'rejected', reason=str(error))
            except (OSError, urllib.error.URLError):
                print(f'候选 {incoming.name} 暂未完成校验，下次重试', flush=True)
    for approval in sorted(APPROVALS.glob('*.json')):
        run_id = approval.stem
        status = RECEIPTS / approval.name
        if RUN.fullmatch(run_id) and status.is_file() and json.loads(status.read_text())['state'] != 'published':
            try:
                publish(run_id, credentials)
            except Rejected as error:
                receipt(run_id, 'signing-failed', reason=str(error))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('submit', 'status', 'approve', 'worker', 'verify-apk'))
    parser.add_argument('identifier', nargs='?')
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if args.action == 'worker':
        work()
    elif args.action == 'verify-apk' and os.geteuid() == 0:
        path = Path(args.identifier)
        checked_signer(path)
        print(json.dumps({'version': apk_version(path), 'sha256': hash_file(path)}))
    elif not RUN.fullmatch(args.identifier or ''):
        parser.error('Expected numeric GitHub Actions run ID')
    elif args.action == 'submit':
        submit(args.identifier, args.value or '')
    elif args.action == 'status':
        print((RECEIPTS / (args.identifier + '.json')).read_text(), end='')
    elif args.action == 'approve':
        approve(args.identifier, args.value)


if __name__ == '__main__':
    try:
        main()
    except (Rejected, OSError, urllib.error.URLError) as error:
        raise SystemExit(str(error)) from None
