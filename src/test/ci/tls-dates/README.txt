# TLS date-validity fixtures (test only)

Self-signed CA (`ca.pem`, key deliberately not committed) and three
server certificates covering the certificate validity windows:

| cert | notBefore | notAfter | expected client verdict |
| --- | --- | --- | --- |
| `srv.pem` | test build time | +10y | handshake succeeds |
| `srv-expired.pem` | 2020-01-01 | 2021-01-01 | rejected: expired |
| `srv-future.pem` | 2035-01-01 | 2036-01-01 | rejected: not yet valid |

All server certs share one key and identity (CN=localhost,
SAN IP:127.0.0.1, DNS:localhost) so only the validity dates differ.
The RSA keys are fixed fixtures for a deterministic local test
harness — they protect nothing real, like `scenarios/tls/`.

Used by `test_tls_dates.py`; generated once with OpenSSL 4.0.3, which
is not needed to run the test.
