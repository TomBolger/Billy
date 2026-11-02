"""Tiny local server telling the emulator which demo scene to play.
GET /scene returns the contents of the file given as argv[1]."""
import http.server
import sys

SCENE_FILE = sys.argv[1]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            body = open(SCENE_FILE).read().strip().encode()
        except OSError:
            body = b""
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
