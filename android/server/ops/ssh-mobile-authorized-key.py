#!/usr/bin/env python3
"""Verify or replace the exact forced command on the dedicated mobile key."""
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

FINGERPRINT = "SHA256:MJw5Wm63bBOjKnkb2rYSS7AOz7XKSqVDxB/tJuPKKU4"
OPTIONS = b'restrict,port-forwarding,permitopen="127.0.0.1:8766",permitopen="127.0.0.1:8768",command='
DISABLED = b'"/bin/false"'
ENABLED = b'"/usr/bin/env -u NODE_OPTIONS -u NODE_PATH /usr/local/bin/node /home/xux/.local/lib/cloud-workbench/ops/ssh-mobile-token.mjs"'


def run(mode, authorized, public):
    if mode not in ("check-disabled", "check-enabled", "enable", "disable"):
        raise ValueError("Invalid authorized key operation")
    printed = subprocess.check_output(
        ["/usr/bin/ssh-keygen", "-lf", os.fspath(public), "-E", "sha256"],
        text=True, stderr=subprocess.DEVNULL,
    ).split()
    if len(printed) < 2 or printed[1] != FINGERPRINT:
        raise ValueError("Mobile SSH public key fingerprint does not match")
    kind, blob, *_ = public.read_bytes().strip().split()
    info = authorized.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("Unsafe authorized_keys file")
    before = authorized.read_bytes()
    identity = re.escape(kind) + rb"[ \t]+" + re.escape(blob)
    if len(re.findall(rb"(?:^|[ \t])" + identity + rb"(?=[ \t\r\n]|$)", before, re.MULTILINE)) != 1:
        raise ValueError("Expected exactly one mobile SSH key occurrence")
    old, new = (DISABLED, ENABLED) if mode in ("check-disabled", "enable") else (ENABLED, DISABLED)
    line = re.compile(rb"^" + re.escape(OPTIONS + old) + rb"(?=[ \t]+" + identity + rb"(?:[ \t][^\r\n]*)?\r?$)", re.MULTILINE)
    after, count = line.subn(lambda _: OPTIONS + new, before)
    if count != 1:
        raise ValueError("Mobile SSH options/forced command differ from the verified baseline")
    if mode.startswith("check-"):
        return
    fd, temporary = tempfile.mkstemp(prefix=".authorized-keys.mobile.", dir=authorized.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(after)
            output.flush()
            os.fsync(output.fileno())
        current = authorized.lstat()
        if (current.st_dev, current.st_ino, current.st_mode, current.st_uid) != (
            info.st_dev, info.st_ino, info.st_mode, info.st_uid
        ) or hashlib.sha256(authorized.read_bytes()).digest() != hashlib.sha256(before).digest():
            raise ValueError("authorized_keys changed concurrently")
        os.replace(temporary, authorized)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == "__main__":
    try:
        if len(sys.argv) != 4:
            raise ValueError("Expected mode, authorized_keys, and reference public key")
        run(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, subprocess.SubprocessError):
        print("Mobile SSH authorized key could not be verified/updated", file=sys.stderr)
        raise SystemExit(1)
