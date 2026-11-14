#!/usr/bin/env python3
"""Post-commit cleanup must retain recovery's staged manifest while
releasing memory. Both shapes are exercised in disposable fixtures.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

try:
    import resource
except ImportError:
    resource = None

parser = argparse.ArgumentParser()
parser.add_argument("--driver", required=True)
args = parser.parse_args()
if resource is not None:
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

JID = "0123456789abcdef"
STAGED = b'{"kind":"grm.manifest","schema_version":1,"locked":true}\n'
JOURNAL = (b'{"kind":"grm.transaction","schema_version":1,"id":"%s",'
           b'"op":"install","ref":"fixture:owner/tool","phase":"committing",'
           b'"created_at":"2026-11-14T00:00:00Z"}\n' % JID.encode())

for mode in ["committed", "uncommitted"]:
    root = Path(tempfile.mkdtemp(prefix=f"postcommit-{mode}-")).resolve()
    mtmp = root / f"grm_manifest.json.tmp-{JID}"
    journal = root / "grm_transaction.json"
    mtmp.write_bytes(STAGED)
    journal.write_bytes(JOURNAL)
    env = dict(os.environ, APEGET_NO_SELF="1")
    env.pop("APEGET_TEST_CRASH", None)
    result = subprocess.run([args.driver, str(root), mode], env=env, cwd=root,
                            capture_output=True, text=True, timeout=30)
    print(result.stdout, end="", flush=True)
    print(result.stderr, end="", flush=True)
    assert result.returncode == 0, (mode, result.returncode, str(root))
    want = "mtmp=1" if mode == "committed" else "mtmp=0"
    assert want in result.stdout, (mode, result.stdout)
    if mode == "committed":
        # journal survives (pipe_ctx_free keeps it) and with it the staged
        # manifest the journal names as manifest_tmp
        assert journal.exists(), mode
        assert mtmp.exists(), mode
        assert mtmp.read_bytes() == STAGED, mode
    else:
        assert not journal.exists(), mode
        assert not mtmp.exists(), mode
    print(f"{mode}: cleanup matches the transaction phase", flush=True)
