"""Publish an already-built site on this server without changing Nginx config."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
LIVE = Path('/var/www/qiwuli.com')


def check_build(dist):
    if not (dist / 'index.html').is_file():
        raise ValueError('Build first: npm run build')
    files = sorted(p for p in dist.rglob('*') if p.is_file())
    if any(p.is_symlink() for p in dist.rglob('*')):
        raise ValueError('Build output must not contain symlinks')
    html = (dist / 'index.html').read_text('utf-8')
    assets = re.findall(r'(?:src|href)="(/assets/[^"?#]+)"', html)
    if not assets or any(not (dist / asset.lstrip('/')).is_file() for asset in assets):
        raise ValueError('Build contains missing entry assets')
    if any('..' in Path(asset).parts for asset in assets):
        raise ValueError('Invalid entry asset path')
    return files


def publish(dist, live, backup):
    files = check_build(dist)
    if live.is_symlink() or any(p.is_symlink() for p in live.rglob('*')):
        raise ValueError('Live directory must not contain symlinks')
    # Save the current version before writing; keep historical hashed assets live.
    shutil.copytree(live, backup)
    staged = []
    try:
        for source in files:
            relative = source.relative_to(dist)
            destination = live / relative
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.qiwuli-', delete=False) as out:
                temporary = Path(out.name)
                staged.append((temporary, destination))
                out.write(source.read_bytes())
                out.flush()
                os.fsync(out.fileno())
            temporary.chmod(0o644)
        # Replace the entry last so it never refers to unavailable assets.
        for temporary, destination in sorted(staged, key=lambda pair: pair[1].name == 'index.html'):
            temporary.replace(destination)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)
    return len(files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate the build without publishing or needing sudo')
    args = parser.parse_args()
    dist = ROOT / 'dist'
    files = check_build(dist)
    if args.check:
        print(f'PASS: {len(files)} build artifacts ready; no live files changed.')
        return
    if os.geteuid() != 0:
        raise SystemExit(f'Publishing requires write access to {LIVE}. Run: sudo python3 {ROOT / "deploy_local.py"}')
    subprocess.run(['/usr/sbin/nginx', '-t'], check=True)
    parent = Path('/var/backups/qiwuli.com')
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup = parent / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    count = publish(dist, LIVE, backup)
    print(f'Published {count} files to {LIVE}. Backup: {backup}')
    print(f'Validate: python3 {ROOT / "tests/live_smoke.py"}')


if __name__ == '__main__':
    main()
