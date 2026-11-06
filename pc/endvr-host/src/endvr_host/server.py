import asyncio
import time
from aiohttp import web
from .metrics import HostMetrics
from .test_pattern import make_test_frame
from .timing import advance_frame_deadline

BOUNDARY = "endvrframe"

class EndVRHost:
    def __init__(self, width=960, height=540, fps=30):
        if not 160 <= width <= 1920 or not 120 <= height <= 1080:
            raise ValueError("resolution must be between 160x120 and 1920x1080")
        if not 1 <= fps <= 60:
            raise ValueError("fps must be between 1 and 60")
        self.width, self.height, self.fps = width, height, fps
        self.metrics = HostMetrics.create()
        self.app = web.Application()
        self.app.add_routes([web.get("/", self.index), web.get("/health", self.health), web.get("/api/v1/status", self.status), web.get("/stream.mjpg", self.stream)])

    async def index(self, request):
        page = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>EndVR EV-0</title><style>body{background:#10151d;color:#e8eef7;font:16px system-ui;margin:2rem}img{width:min(100%,960px);border:1px solid #526176}pre{white-space:pre-wrap}</style></head><body><h1>EndVR PC Host — EV-0</h1><p>Synthetic test pattern only; desktop capture and WebRTC are not implemented yet.</p><img src='/stream.mjpg'><h2>Status</h2><pre id='status'>Loading...</pre><script>async function tick(){try{let r=await fetch('/api/v1/status');document.getElementById('status').textContent=JSON.stringify(await r.json(),null,2)}catch(e){document.getElementById('status').textContent=String(e)}}tick();setInterval(tick,1000)</script></body></html>"
        return web.Response(text=page, content_type="text/html")

    async def health(self, request):
        return web.json_response({"ok": True, "service": "endvr-host", "stage": "EV-0"})

    async def status(self, request):
        return web.json_response({"service":"endvr-host", "stage":"EV-0", "source":"synthetic-test-pattern", "transport":"mjpeg-over-http", "width":self.width, "height":self.height, "target_fps":self.fps, "metrics":self.metrics.snapshot()})

    async def stream(self, request):
        response = web.StreamResponse(headers={"Content-Type":f"multipart/x-mixed-replace; boundary={BOUNDARY}", "Cache-Control":"no-store", "X-Content-Type-Options":"nosniff"})
        await response.prepare(request)
        self.metrics.client_connected()
        frame_number, period = 0, 1.0 / self.fps
        deadline = time.monotonic()
        try:
            while True:
                jpeg = make_test_frame(self.width, self.height, frame_number)
                self.metrics.frame_generated()
                header = (f"--{BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: {len(jpeg)}\r\n\r\n").encode("ascii")
                await response.write(header + jpeg + b"\r\n")
                frame_number += 1
                deadline = advance_frame_deadline(deadline, period, time.monotonic())
                await asyncio.sleep(max(0.0, deadline-time.monotonic()))
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        finally:
            self.metrics.client_disconnected()
        return response
