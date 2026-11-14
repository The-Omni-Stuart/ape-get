#!/usr/bin/env python3
"""Certificate validity windows must gate the TLS handshake (review N01).

Serves each fixture certificate from tls-dates/ over one local HTTPS
connection and drives the production client handshake through
--driver. Only the validity dates differ between certificates, so a
pass here means notBefore/notAfter were checked against the clock —
the failure mode was a custom mbed TLS config that compiled the date
comparison out entirely.
"""
import argparse
import socket
import ssl
import subprocess
import sys
import threading
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent / "tls-dates"

CASES = [
    ("valid", "srv.pem", "srv.key", True, None),
    ("expired", "srv-expired.pem", "srv-expired.key", False, "expired"),
    ("future", "srv-future.pem", "srv-future.key", False, "not yet"),
]


def listen(port: int) -> socket.socket:
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    srv.settimeout(30)
    return srv


def serve_once(srv: socket.socket, cert: str, key: str) -> None:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(str(FIXTURES / cert), str(FIXTURES / key))
    try:
        conn, _ = srv.accept()
        try:
            # a client that rejects the certificate fails the TLS layer
            # here; the handshake never completes and this raises
            with ctx.wrap_socket(conn, server_side=True) as tls:
                tls.recv(16)
        except OSError:
            pass
    finally:
        srv.close()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", required=True)
    args = ap.parse_args()

    failures = 0
    for name, cert, key, expect_ok, needle in CASES:
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        srv = listen(port)   # listening before the driver runs

        server = threading.Thread(target=serve_once, args=(srv, cert, key))
        server.start()
        try:
            run = subprocess.run(
                [args.driver, str(port), str(FIXTURES / "ca.pem")],
                capture_output=True, text=True, timeout=60)
        finally:
            server.join(timeout=30)
        got = (run.stdout + run.stderr).strip()
        ok = run.returncode == 0

        if ok != expect_ok:
            print(f"FAIL {name}: expected handshake"
                  f"{' ok' if expect_ok else ' rejection'}, got {got!r}")
            failures += 1
            continue
        if needle and needle not in got.lower():
            print(f"FAIL {name}: rejection reason {got!r} "
                  f"does not mention {needle!r}")
            failures += 1
            continue
        print(f"PASS {name}: {got.splitlines()[0] if got else ''}")

    if failures:
        print(f"{failures} case(s) failed")
        return 1
    print(f"all {len(CASES)} validity-window cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
