#!/usr/bin/env python3
"""XZ trailing bytes must be refused even at the input-buffer boundary.

The decoder reads the envelope in 4096-byte chunks; a member that ends
exactly at a chunk boundary used to hide any bytes appended after it.
The fixture is deterministic (seeded LCG, preset 0): its stream is
exactly 4096 bytes, so the first read lands flush on the stream end.
"""
import argparse
import lzma
import os
import subprocess
import sys
import tempfile


def lcg(n):
    out = bytearray(n)
    s = 0x2545F491
    for i in range(n):
        s = (s * 1103515245 + 12345) & 0x7FFFFFFF
        out[i] = (s >> 16) & 0xFF
    return bytes(out)


def stream_at_4096():
    """An xz stream of exactly 4096 bytes, or hard failure (deterministic:
    the search either finds n=4036 on every host or the module changed)."""
    data = lcg(4036)
    stream = lzma.compress(data, preset=0)
    if len(stream) != 4096:
        raise SystemExit(f"fixture generator drifted: stream is "
                         f"{len(stream)} bytes, expected 4096")
    assert stream[:6] == b"\xfd7zXZ\x00"
    return data, stream


def decode(driver, path, expect_ok, out_dir, note):
    out = os.path.join(out_dir, "decoded.bin")
    if os.path.exists(out):
        os.remove(out)
    r = subprocess.run([driver, path, out], capture_output=True, text=True)
    ok = r.returncode == 0
    if ok != expect_ok:
        print(f"FAIL {note}: rc={r.returncode} want rc="
              f"{'0' if expect_ok else 'non-zero'}")
        return 1
    if not expect_ok and os.path.exists(out):
        print(f"FAIL {note}: refusal left an output file behind")
        return 1
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", required=True)
    args = ap.parse_args()

    data, stream = stream_at_4096()
    fails = 0
    with tempfile.TemporaryDirectory() as td:
        full = os.path.join(td, "full.xz")
        with open(full, "wb") as f:
            f.write(stream)
        print(f"fixture: stream is {len(stream)} bytes "
              f"(4096-boundary: {len(stream) % 4096 == 0})")

        # a legitimate single-stream file at the boundary still decodes
        fails += decode(args.driver, full, True, td, "clean boundary stream")

        # junk right at the 4096 read boundary: invisible to a decoder
        # that only trusts its input buffer, not the file
        for junk in (b"X", b"EXTRA_TRAILING_BYTES"):
            trailing = os.path.join(td, "trailing.xz")
            with open(trailing, "wb") as f:
                f.write(stream + junk)
            fails += decode(args.driver, trailing, False, td,
                            f"boundary stream + {junk!r}")

        # concatenated streams stay refused (control at the boundary too)
        concat = os.path.join(td, "concat.xz")
        with open(concat, "wb") as f:
            f.write(stream + stream)
        fails += decode(args.driver, concat, False, td, "doubled stream")

        # an in-buffer tail was already caught before; keep that pinned
        small = lzma.compress(b"small payload for the in-buffer case",
                              preset=0)
        smalltrailing = os.path.join(td, "smalltrailing.xz")
        with open(smalltrailing, "wb") as f:
            f.write(small + b"tail")
        fails += decode(args.driver, smalltrailing, False, td,
                        "short stream + tail")

        # the clean decode really produced the payload
        clean_ok = subprocess.run([args.driver, full,
                                   os.path.join(td, "again.bin")])
        if clean_ok.returncode == 0:
            with open(os.path.join(td, "again.bin"), "rb") as f:
                if f.read() != data:
                    print("FAIL: decoded payload differs")
                    fails += 1

    if fails:
        print(f"xz_tail: {fails} failed")
        return 1
    print("xz_tail: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
