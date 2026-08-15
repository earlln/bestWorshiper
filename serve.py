#!/usr/bin/env python3
import http.server, sys, os

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
os.chdir(os.path.dirname(os.path.abspath(__file__)))

class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        '.json': 'application/json',
        '.png': 'image/png',
        '.js': 'application/javascript',
    }
    def log_message(self, fmt, *args):
        print(f"[{self.address_string()}] {fmt % args}")

print(f"bestWorshiper 서버 시작: http://localhost:{PORT}/bestWorshiper.html")
print("종료: Ctrl+C")
http.server.HTTPServer(('', PORT), Handler).serve_forever()
