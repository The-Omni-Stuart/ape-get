#!/usr/bin/env python3
"""Localhost HTTPS mock provider for CLI scenarios.

Serves canned GitHub/Gitea/GitLab JSON from a fixture tree on the pinned
port 127.0.0.1:18081 with the fixed certificate in scenarios/tls/.
A GET /some/path serves <fixtures>/some/path.json; anything else is the
GitHub-shaped 404. Started by run.py for scenarios tagged mock=1.

  mock_provider.py <fixtures-dir> [--ready-file path]
"""
import http.server
import json
import os
import ssl
import sys
import threading
import urllib.parse
from pathlib import Path

HOST, PORT = "127.0.0.1", 18081
TLS = Path(__file__).resolve().parent / "tls"
FIXTURES = Path(sys.argv[1]).resolve()

# Synthetic streaming routes (download-group scenarios): the bytes are
# generated, so multi-MB bodies cost the repo nothing.
STREAM_TOTAL = 2_000_000
STREAM_SEED = b"ape-get chunked download fixture\n"
ABORT_TOTAL = 2_000_000
ABORT_SENT = 500_000


def stream_bytes(n):
    return (STREAM_SEED * (n // len(STREAM_SEED) + 1))[:n]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
        if path == "/stream/big":
            self._serve_chunked(stream_bytes(STREAM_TOTAL))
            return
        if path == "/stream/aborted":
            self._serve_aborted(ABORT_TOTAL, ABORT_SENT)
            return
        if path == "/redir/to-asset":
            self.send_response(302)
            self.send_header("Location",
                             "/redir/authenticated/assets/tool.AppImage")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path.startswith("/redir/authenticated/"):
            if self.headers.get("Authorization") is None:
                self._serve(json.dumps({"message": "Unauthorized"}).encode(),
                            401, "application/json")
                return
            rest = path[len("/redir/authenticated/"):]
            cand = (FIXTURES / rest).resolve()
            if str(cand).startswith(str(FIXTURES)) and cand.is_file():
                self._serve(cand.read_bytes(), 200,
                            "application/octet-stream")
            else:
                self._serve(json.dumps({"message": "Not Found"}).encode(),
                            404, "application/json")
            return
        if path.startswith("/trunc/") and path.endswith("/releases"):
            # valid JSON body under a Content-Length that never arrives:
            # strict framing must call the truncation out (install group
            # truncated-release)
            body = json.dumps([{"id": 9, "tag_name": "1.0.0",
                                "draft": False, "prerelease": False,
                                "assets": [{
                                    "name": "tool-1.0.0.AppImage",
                                    "browser_download_url":
                                        "https://127.0.0.1:18081/assets/"
                                        "tool-1.0.0.AppImage"}]}]).encode()
            self.protocol_version = "HTTP/1.1"
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body) + 1000))
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        body, code = None, 404
        base = FIXTURES / path.lstrip("/")
        # raw assets: an exact fixture file (e.g. a fake .AppImage) is
        # served byte-for-byte; API paths fall back to <path>.json
        for cand, ctype in (
            (base, "application/octet-stream"),
            (FIXTURES / (path.lstrip("/") + ".json"), "application/json"),
            (base.with_suffix(".json"), "application/json"),
        ):
            if cand.is_file() and str(cand.resolve()).startswith(str(FIXTURES)):
                body, code = cand.read_bytes(), 200
                self._serve(body, code, ctype)
                return
        self._serve(json.dumps({"message": "Not Found"}).encode(), 404,
                    "application/json")

    def _serve(self, body, code, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_chunked(self, body):
        # chunked transfer encoding: no Content-Length, exercising the
        # streaming decoder on the client
        self.protocol_version = "HTTP/1.1"
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for i in range(0, len(body), 65536):
            part = body[i:i + 65536]
            self.wfile.write(b"%x\r\n%s\r\n" % (len(part), part))
        self.wfile.write(b"0\r\n\r\n")
        self.close_connection = True

    def _serve_aborted(self, announced, sent):
        # close the connection mid-body (after `sent` of `announced`
        # bytes): exercises the client's mid-transfer failure path
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(announced))
        self.end_headers()
        self.wfile.write(stream_bytes(sent))
        self.wfile.flush()
        self.close_connection = True

    def log_message(self, *a):    # keep scenario stderr clean
        pass


def main():
    srv = http.server.ThreadingHTTPServer((HOST, PORT), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(TLS / "cert.pem", TLS / "key.pem")
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    if len(sys.argv) > 2 and sys.argv[2] == "--ready-file":
        Path(sys.argv[3]).write_text("ready")
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    os.chdir("/")
    main()
