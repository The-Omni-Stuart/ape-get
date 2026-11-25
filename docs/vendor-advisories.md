# Vendor advisories and TLS-stack maintenance plan

Owner: The-Omni-Stuart (org)
Review cadence: every vendor PIN bump, and a full advisory sweep of this
table at least every 90 days (also mandatory before any release tag, since
the size, lifecycle and gate work all run through these pins).

## What the current pins fix (bounded advisory check, not a claim of no
vulnerabilities)

| Pin | Version | Why this version |
|---|---|---|
| `vendor/mbedtls/PIN` | 3.6.7 | Primary upstream advisories say this version fixes the named July-2026 CA-bit and extended-master-secret issues. Do not incorrectly label the version vulnerable to those fixed issues. |
| `vendor/liblzma/PIN` | 5.8.4 | Upstream identifies 5.8.4 as the September-2026 security-fix release. |
| `vendor/xdelta3/PIN` | 3.2.2 | Decoder subset only; encoder, CLI and secondaries are never compiled (see PIN). |
| `vendor/ca-bundle/PIN` | fetched 2026-10-28 | Mozilla bundle; refresh is a dedicated commit re-running the TLS spike and size gates. |

## Lifecycle horizon (this is the reason for the cadence above)

- The official maintainers announced on 2026-10-08 that their maintenance
  involvement in the TLS/X.509 libraries ends **April 2027**.
- The 3.6 LTS lifecycle is documented through **March 2027**. This project
  is starting only months before that transition.
- Continuation plan: evaluate supported TLS/X.509 alternatives and measure
  their binary/runtime costs against the fat-APE size gates. Do not replace
  the stack solely for style.

## Primary sources

- <https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.7>
- <https://mbed-tls.readthedocs.io/en/latest/security-advisories/mbedtls-security-advisory-2026-07-x509-ca-bit-forgery-invalid-basicconstraints/>
- <https://mbed-tls.readthedocs.io/en/latest/security-advisories/mbedtls-security-advisory-2026-07-extended-master-secret-calculation-failure-ignored/>
- <https://lists.trustedfirmware.org/archives/list/mbed-tls-announce@lists.trustedfirmware.org/message/YPIGCB7WN6GCHOGEGKOJXUYAP7OK527W/>
- <https://github.com/Mbed-TLS/mbedtls/blob/development/BRANCHES.md>
- <https://tukaani.org/xz/>
- <https://github.com/jmacd/xdelta/releases/tag/v3.2.2>

`bin/vendor-pin-check.sh` verifies these pins' bytes on every CI push.
