#!/usr/bin/env python3
"""Haven Video Server - receives and serves motion-triggered video clips.

Usage:
    python3 haven_server.py --token YOUR_SECRET_TOKEN --port 8080 --storage /data/haven-videos

Deploy on Raspberry Pi, point Cloudflare tunnel to localhost:8080.
"""

import argparse
import cgi
import html
import os
import re
import sys
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from socketserver import ThreadingMixIn

MAX_UPLOAD_SIZE = 100 * 1024 * 1024  # 100 MB


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


class HavenHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        sys.stderr.write("[%s] %s\n" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), format % args))

    def check_auth(self):
        token = self.server.auth_token
        if not token:
            return True
        auth_header = self.headers.get("Authorization", "")
        # Accept token in both header and query param for browser access
        if auth_header == f"Bearer {token}":
            return True
        # Check query param ?token=... for browser access
        if "?" in self.path:
            query = self.path.split("?", 1)[1]
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            if params.get("token") == token:
                return True
        self.send_response(401)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"Unauthorized")
        return False

    def do_POST(self):
        path = self.path.split("?")[0]
        if path != "/upload":
            self.send_error(404)
            return
        if not self.check_auth():
            return

        content_length = int(self.headers.get("Content-Length", 0))
        if content_length > MAX_UPLOAD_SIZE:
            self.send_error(413, "File too large")
            return

        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.send_error(400, "Expected multipart/form-data")
            return

        form = cgi.FieldStorage(
            fp=self.rfile,
            headers=self.headers,
            environ={
                "REQUEST_METHOD": "POST",
                "CONTENT_TYPE": content_type,
                "CONTENT_LENGTH": str(content_length),
            },
        )

        file_item = form["video"] if "video" in form else None
        if file_item is None or not file_item.filename:
            self.send_error(400, "No video file in upload")
            return

        # Sanitize filename
        filename = sanitize_filename(file_item.filename)
        if not filename:
            filename = datetime.now().strftime("clip_%Y%m%d_%H%M%S.mp4")

        # Save to date directory
        date_dir = self.server.storage_dir / datetime.now().strftime("%Y-%m-%d")
        date_dir.mkdir(parents=True, exist_ok=True)

        dest = date_dir / filename
        # Avoid collisions
        if dest.exists():
            stem = dest.stem
            suffix = dest.suffix
            counter = 1
            while dest.exists():
                dest = date_dir / f"{stem}_{counter}{suffix}"
                counter += 1

        data = file_item.file.read()

        # Basic MP4 validation (check for ftyp box)
        if len(data) >= 8 and b"ftyp" not in data[:32]:
            self.send_error(400, "File does not appear to be an MP4")
            return

        with open(dest, "wb") as f:
            f.write(data)

        self.log_message("Saved: %s (%d bytes)", dest, len(data))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok","file":"%s"}' % dest.name.encode())

    def do_GET(self):
        path = self.path.split("?")[0]

        if path.startswith("/videos/"):
            self.serve_video(path)
            return

        if path == "/" or path == "":
            if not self.check_auth():
                return
            self.serve_index()
            return

        self.send_error(404)

    def serve_video(self, path):
        if not self.check_auth():
            return

        # /videos/2026-03-29/filename.mp4
        parts = path.split("/")
        if len(parts) != 4:
            self.send_error(404)
            return

        date_str = sanitize_filename(parts[2])
        filename = sanitize_filename(parts[3])
        filepath = self.server.storage_dir / date_str / filename

        if not filepath.exists() or not filepath.is_file():
            self.send_error(404)
            return

        # Ensure file is within storage dir
        try:
            filepath.resolve().relative_to(self.server.storage_dir.resolve())
        except ValueError:
            self.send_error(403)
            return

        file_size = filepath.stat().st_size
        range_header = self.headers.get("Range")

        if range_header:
            self.serve_range(filepath, file_size, range_header)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(file_size))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            with open(filepath, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)

    def serve_range(self, filepath, file_size, range_header):
        match = re.match(r"bytes=(\d+)-(\d*)", range_header)
        if not match:
            self.send_error(416)
            return

        start = int(match.group(1))
        end = int(match.group(2)) if match.group(2) else file_size - 1
        end = min(end, file_size - 1)

        if start > end or start >= file_size:
            self.send_error(416)
            return

        length = end - start + 1

        self.send_response(206)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(length))
        self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()

        with open(filepath, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk_size = min(65536, remaining)
                chunk = f.read(chunk_size)
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def serve_index(self):
        token_param = ""
        if self.server.auth_token:
            # Pass token as query param for video URLs in browser
            query = ""
            if "?" in self.path:
                query = self.path.split("?", 1)[1]
                params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
                if "token" in params:
                    token_param = f"?token={params['token']}"

        storage = self.server.storage_dir
        dates = sorted(
            [d.name for d in storage.iterdir() if d.is_dir() and re.match(r"\d{4}-\d{2}-\d{2}", d.name)],
            reverse=True,
        )

        body_parts = []
        for date in dates:
            date_dir = storage / date
            videos = sorted(
                [f.name for f in date_dir.iterdir() if f.is_file() and f.suffix.lower() == ".mp4"],
                reverse=True,
            )
            if not videos:
                continue

            body_parts.append(f'<h2>{html.escape(date)}</h2>')
            body_parts.append('<div class="grid">')
            for v in videos:
                video_url = f"/videos/{html.escape(date)}/{html.escape(v)}{token_param}"
                body_parts.append(f"""
                <div class="card">
                    <video controls preload="metadata" playsinline>
                        <source src="{video_url}" type="video/mp4">
                    </video>
                    <p>{html.escape(v)}</p>
                </div>""")
            body_parts.append("</div>")

        if not body_parts:
            body_parts.append("<p>No videos yet. Clips will appear here when motion is detected.</p>")

        page = HTML_TEMPLATE.replace("{{BODY}}", "\n".join(body_parts))

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(page.encode("utf-8"))


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Haven Clips</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         background: #1a1a2e; color: #e0e0e0; padding: 20px; }
  h1 { color: #e94560; margin-bottom: 20px; }
  h2 { color: #0f3460; background: #16213e; padding: 10px 15px; border-radius: 8px;
       margin: 20px 0 10px; font-size: 1.1em; color: #e0e0e0; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
          gap: 15px; padding: 10px 0; }
  .card { background: #16213e; border-radius: 8px; overflow: hidden;
          box-shadow: 0 2px 8px rgba(0,0,0,0.3); }
  .card video { width: 100%; display: block; }
  .card p { padding: 8px 12px; font-size: 0.85em; color: #a0a0a0;
            white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  @media (max-width: 600px) {
    .grid { grid-template-columns: 1fr; }
    body { padding: 10px; }
  }
</style>
</head>
<body>
<h1>Haven Clips</h1>
{{BODY}}
</body>
</html>"""


def sanitize_filename(name):
    """Remove path separators and other unsafe characters."""
    name = os.path.basename(name)
    name = re.sub(r"[^\w.\-]", "_", name)
    return name


def main():
    parser = argparse.ArgumentParser(description="Haven Video Server")
    parser.add_argument("--port", type=int, default=8080, help="Port to listen on")
    parser.add_argument("--token", default=os.environ.get("HAVEN_TOKEN", ""),
                        help="Bearer auth token (or set HAVEN_TOKEN env var)")
    parser.add_argument("--storage", default="/data/haven-videos",
                        help="Directory to store uploaded videos")
    args = parser.parse_args()

    storage = Path(args.storage)
    storage.mkdir(parents=True, exist_ok=True)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), HavenHandler)
    server.auth_token = args.token
    server.storage_dir = storage

    print(f"Haven Video Server running on port {args.port}")
    print(f"Storage: {storage.resolve()}")
    if args.token:
        print(f"Auth: enabled (token set)")
    else:
        print("Auth: DISABLED (no token set, anyone can access)")
    print(f"\nBrowser:  http://localhost:{args.port}/" +
          (f"?token={args.token}" if args.token else ""))
    print(f"Upload:   POST http://localhost:{args.port}/upload")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
