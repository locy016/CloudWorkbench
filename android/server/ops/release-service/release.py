#!/usr/bin/python3
import argparse
import base64
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
from contextlib import contextmanager
from pathlib import Path


REPO = 'locy016/CloudWorkbench'
API = f'https://api.github.com/repos/{REPO}'
WORKFLOW = '.github/workflows/cloud-workbench-ci.yml'
PACKAGE = 'cn.xux.cloudworkbench'
CERTIFICATE = 'b5aaa0f7308677f16157d37260884e4d51b2cd52edc2d373722ff52be126014a'
ROOT = Path('/var/lib/cloud-workbench-release')
INBOX = Path('/var/spool/cloud-workbench-release/inbox')
DEVICE_INPUT = INBOX.parent / 'device-input'
REQUESTS = ROOT / 'requests'
APPROVALS = ROOT / 'approvals'
RECEIPTS = ROOT / 'receipts'
UPDATES = ROOT / 'updates'
PUBLISHER = Path('/usr/local/lib/cloud-workbench-release/ops/publish-app-update.mjs')
LIMIT = 384 * 1024 * 1024
APK = 'app-release-unsigned.apk'
SHA = re.compile(r'[0-9a-f]{64}')
RUN = re.compile(r'[1-9][0-9]{0,17}')
LOCAL_RUN = re.compile(r'90[0-9]{13}')
LOCAL_SIGNING_NAMESPACE = 'cloud-workbench-local-apk-v1'
CHECKS = {'gateway': '运行网关测试', 'lint': 'Android Lint', 'apk': '构建并检查未签名 APK'}


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


def accepted_checks(run_id):
    jobs = github_json(f'/actions/runs/{run_id}/jobs?per_page=30').get('jobs', [])
    passed = {step['name'] for job in jobs if job.get('conclusion') == 'success'
              for step in job.get('steps', []) if step.get('conclusion') == 'success'}
    if not all(name in passed for name in CHECKS.values()):
        raise Rejected('网关测试、Android Lint 和未签名 APK 构建均须通过')
    return dict.fromkeys(CHECKS, 'success')


def accept_local(run_id, incoming, staged):
    if not LOCAL_RUN.fullmatch(run_id):
        raise Rejected('本机构建编号无效')
    allowed_signers = APPROVALS / 'local-builders'
    if not allowed_signers.is_file():
        raise Rejected('本机构建发布尚未配置可信开发机')
    with safe_open('build.json', 16 * 1024, incoming) as offered:
        evidence = offered.read(16 * 1024 + 1)
    with safe_open('build.json.sig', 4096, incoming) as offered:
        proof = staged / 'build.json.sig'
        proof.write_bytes(offered.read(4097))
    verify = subprocess.run(['/usr/bin/ssh-keygen', '-Y', 'verify', '-f', str(allowed_signers), '-I', 'workbench-windows',
                             '-n', LOCAL_SIGNING_NAMESPACE, '-s', str(proof)], input=evidence, capture_output=True, timeout=10)
    if verify.returncode:
        raise Rejected('本机构建记录签名无效')
    proof.unlink()
    record = json.loads(evidence)
    if (not isinstance(record, dict) or record.get('runId') != run_id or record.get('checks') != dict.fromkeys(CHECKS, 'success') or
            not isinstance(record.get('source'), str) or not re.fullmatch(r'[0-9a-f]{40}', record['source']) or
            not isinstance(record.get('apkSha256'), str) or not SHA.fullmatch(record['apkSha256']) or
            not isinstance(record.get('notes'), str) or not 0 < len(record['notes']) <= 2000):
        raise Rejected('本机构建记录无效')
    comparison = github_json(f"/compare/{record['source']}...main?per_page=1")
    if comparison.get('status') not in ('ahead', 'identical') or comparison.get('base_commit', {}).get('sha') != record['source']:
        raise Rejected('本机构建源码未纳入 main')
    package = staged / APK
    with safe_open(APK, LIMIT, incoming) as offered, package.open('xb') as stored:
        if copy_bytes(offered, stored, LIMIT) != record['apkSha256']:
            raise Rejected('本机 APK 与签名构建记录的摘要不一致')
    code, version = apk_version(package)
    if (code, version) != (record.get('versionCode'), record.get('versionName')):
        raise Rejected('本机 APK 与签名构建记录的版本不一致')
    return {**{name: record[name] for name in ('runId', 'source', 'apkSha256', 'notes', 'checks')},
            'artifactId': int(run_id), 'versionCode': code, 'versionName': version, 'digestVersion': 2}


def candidate_digest(data):
    fields = ('runId', 'source', 'artifactId', 'apkSha256', 'versionCode', 'versionName', 'notes')
    parts = [str(data[field]) for field in fields] + [data['checks'][key] for key in CHECKS]
    encoded = b'\n'.join(base64.b64encode(value.encode('utf-8')) for value in parts)
    return hashlib.sha256(b'WB-RELEASE-CANDIDATE/2\n' + encoded + b'\n').hexdigest()


def decision_message(run_id, digest, decision):
    return f'WB-RELEASE-DECISION/1\n{run_id}\n{digest}\n{decision}\n'.encode('ascii')


@contextmanager
def lock_decisions():
    import fcntl
    # Both the administrator and the signing account can open this directory.
    # Lock its inode so the first caller cannot create a root-only lock file.
    directory = os.open(REQUESTS, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        fcntl.flock(directory, fcntl.LOCK_EX)
        yield
    finally:
        os.close(directory)


def enroll(fingerprint):
    if os.geteuid() != 0 or not SHA.fullmatch(fingerprint or ''):
        raise Rejected('管理员须输入从平板核对的审批公钥指纹')
    with safe_open(DEVICE_INPUT / 'approvers' / (fingerprint + '.der'), 256) as file:
        der = file.read(257)
    if len(der) > 256 or hashlib.sha256(der).hexdigest() != fingerprint:
        raise Rejected('审批公钥指纹不匹配')
    info = subprocess.run(['/usr/bin/openssl', 'pkey', '-pubin', '-inform', 'DER', '-text_pub', '-noout'],
                          input=der, capture_output=True, timeout=10, check=False)
    if info.returncode or b'ASN1 OID: prime256v1' not in info.stdout:
        raise Rejected('审批公钥必须是 P-256')
    with lock_decisions():
        atomic_json(APPROVALS / 'approver.json', {'fingerprint': fingerprint, 'der': base64.b64encode(der).decode()},
                    0o640, APPROVALS.stat().st_gid)
        atomic_json(RECEIPTS / 'approver.json', {'fingerprint': fingerprint}, 0o640, RECEIPTS.stat().st_gid)
    print(f'已绑定审批设备 {fingerprint}')


def tablet_decision(run_id, data):
    path = DEVICE_INPUT / 'decisions' / (run_id + '.json')
    if not path.is_file() or not (APPROVALS / 'approver.json').is_file():
        return
    protected = REQUESTS / run_id / 'tablet.json'
    with lock_decisions():
        if (APPROVALS / (run_id + '.json')).exists() or protected.exists():
            path.unlink(missing_ok=True)
            return
        try:
            with safe_open(path, 4096) as proof:
                payload = json.load(proof)
            key = json.loads((APPROVALS / 'approver.json').read_text())
            if (not isinstance(payload, dict) or payload.get('approvalSha256') != data['approvalSha256'] or
                    payload.get('fingerprint') != key['fingerprint'] or payload.get('decision') not in ('approve', 'reject') or
                    data.get('digestVersion') != 2 or candidate_digest(data) != data['approvalSha256']):
                raise Rejected('审批内容与已复核候选包不匹配')
            signature = base64.b64decode(payload['signature'], validate=True)
            if not 64 <= len(signature) <= 80:
                raise Rejected('审批签名格式无效')
            with tempfile.TemporaryDirectory(dir=REQUESTS) as temporary:
                public = Path(temporary) / 'key.der'
                proof = Path(temporary) / 'signature'
                public.write_bytes(base64.b64decode(key['der'], validate=True))
                proof.write_bytes(signature)
                result = subprocess.run(['/usr/bin/openssl', 'dgst', '-sha256', '-keyform', 'DER', '-verify', str(public),
                                         '-signature', str(proof)], input=decision_message(run_id, data['approvalSha256'], payload['decision']),
                                        capture_output=True, timeout=10, check=False)
            if result.returncode:
                raise Rejected('审批签名校验失败')
            atomic_json(protected, {**payload, 'approvedAt': int(time.time())})
        except (Rejected, ValueError, KeyError, OSError):
            print(f'构建 {run_id} 未通过平板审批校验', flush=True)
        path.unlink(missing_ok=True)


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


def invoke(*args, report_failure=False):
    result = subprocess.run(args, text=True, capture_output=True, timeout=180, check=False)
    if result.returncode:
        if report_failure and result.stderr:
            print(result.stderr[:2048], flush=True)
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
        if isinstance(record, dict) and record.get('origin') == 'local':
            status = accept_local(run_id, directory, staged)
            incoming_files = ('ready.json', 'build.json', 'build.json.sig', APK)
        else:
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
            status = {'runId': run_id, 'source': source, 'artifactId': artifact['id'], 'apkSha256': apk_sha,
                      'versionCode': code, 'versionName': version, 'notes': notes, 'digestVersion': 2,
                      'checks': accepted_checks(run_id)}
            incoming_files = ('ready.json', 'artifact.zip')
        if status['versionCode'] <= json.loads((UPDATES / 'current.json').read_text())['versionCode']:
            raise Rejected('候选构建号必须高于私有更新源')
        status['approvalSha256'] = candidate_digest(status)
        atomic_json(staged / 'candidate.json', status)
        staged.rename(candidate)
        receipt(run_id, 'awaiting-approval', candidate=status)
        for name in incoming_files:
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
    with lock_decisions():
        if (REQUESTS / run_id / 'tablet.json').exists():
            raise Rejected('该候选包已经由平板审批')
        if not path.exists():
            atomic_json(path, {'sha256': expected, 'approvedAt': int(time.time())}, 0o640, APPROVALS.stat().st_gid)
    print(f'已批准构建 {run_id} {expected}')


def publish(run_id, credentials):
    directory = REQUESTS / run_id
    approval = APPROVALS / (run_id + '.json')
    tablet = directory / 'tablet.json'
    if not approval.is_file() and not tablet.is_file():
        return
    data = json.loads((directory / 'candidate.json').read_text())
    decision = json.loads(tablet.read_text()) if tablet.is_file() else None
    bound = decision['approvalSha256'] if decision else json.loads(approval.read_text())['sha256']
    if bound != data['approvalSha256']:
        raise Rejected('管理员批准摘要与候选包不一致')
    actor = {'by': 'tablet', 'at': decision['approvedAt']} if decision else {'by': 'administrator'}
    if decision and decision['decision'] == 'reject':
        receipt(run_id, 'rejected-by-operator', candidate=data, approval=actor)
        (directory / APK).unlink(missing_ok=True)
        return
    current = json.loads((UPDATES / 'current.json').read_text())
    signed = directory / 'signed.apk'
    def complete(update):
        receipt(run_id, 'published', candidate=data, update=update, approval=actor)
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
    # The systemd unit exposes the request and update directories as separate mounts.
    # Keep the publisher's hard link and manifest rename on the update mount.
    with tempfile.TemporaryDirectory(dir=UPDATES, prefix='.release-') as staging:
        pending = Path(staging)
        manifest = pending / 'manifest.json'
        package = pending / 'update.apk'
        shutil.copyfile(signed, package)
        atomic_json(manifest, update)
        invoke('/usr/bin/flock', str(UPDATES / '.publish.lock'), '/usr/local/bin/node', str(PUBLISHER),
               str(manifest), str(package), str(UPDATES), '--group-readable', report_failure=True)
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
    for candidate in sorted(REQUESTS.iterdir()):
        run_id = candidate.name
        status = RECEIPTS / (run_id + '.json')
        if RUN.fullmatch(run_id) and status.is_file() and json.loads(status.read_text())['state'] not in ('published', 'rejected-by-operator'):
            data = json.loads((candidate / 'candidate.json').read_text())
            tablet_decision(run_id, data)
            try:
                publish(run_id, credentials)
            except Rejected as error:
                receipt(run_id, 'signing-failed', candidate=data, reason=str(error))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('submit', 'status', 'approve', 'worker', 'verify-apk', 'enroll'))
    parser.add_argument('identifier', nargs='?')
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if args.action == 'worker':
        work()
    elif args.action == 'enroll':
        enroll(args.identifier)
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
