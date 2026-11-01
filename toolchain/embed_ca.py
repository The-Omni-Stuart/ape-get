#!/usr/bin/env python3
"""Embed a PEM CA bundle as a DER-concatenated C array (
embedded trust store; DER-instead-of-PEM was the size experiment that
stayed open. DER is what mbed TLS parses after base64, so
this only removes the PEM armour the binary re-decodes at startup.

  embed_ca.py <input.pem> <output.c>

Emits `apeget_ca_der` and `apeget_ca_der_len`: the concatenated DER
certificates in bundle order. $APEGET_CA_BUNDLE overrides at runtime
and stays a PEM file.
"""
import base64
import re
import sys

pem = open(sys.argv[1], "rb").read()
begin = pem.count(b"-----BEGIN CERTIFICATE-----")
blocks = re.findall(
    rb"-----BEGIN CERTIFICATE-----\r?\n(.*?)\r?\n-----END CERTIFICATE-----",
    pem, re.S)
if begin == 0 or len(blocks) != begin:
    sys.exit(f"embed_ca: {begin} BEGIN markers vs {len(blocks)} complete blocks")
der = b"".join(base64.b64decode(b.replace(b"\r", b"").replace(b"\n", b""))
               for b in blocks)

out = open(sys.argv[2], "w")
out.write(f"/* {len(blocks)} certificates, DER-concatenated from PEM. */\n")
out.write("const unsigned char apeget_ca_der[] =\n")
for i in range(0, len(der), 28):
    chunk = der[i:i + 28]
    out.write('    "' + "".join(f"\\x{b:02x}" for b in chunk) + '"\n')
out.write("    \"\";\n")
out.write("const unsigned long apeget_ca_der_len = sizeof(apeget_ca_der);\n")
