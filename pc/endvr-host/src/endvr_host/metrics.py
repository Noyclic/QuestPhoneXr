from dataclasses import dataclass
import time

@dataclass
class HostMetrics:
    started_at: float
    frames_generated: int = 0
    clients_connected: int = 0
    last_frame_at: float | None = None

    @classmethod
    def create(cls):
        return cls(time.monotonic())

    def frame_generated(self):
        self.frames_generated += 1
        self.last_frame_at = time.monotonic()

    def client_connected(self):
        self.clients_connected += 1

    def client_disconnected(self):
        self.clients_connected = max(0, self.clients_connected - 1)

    def snapshot(self):
        now = time.monotonic()
        return {"uptime_seconds": round(max(0.0, now-self.started_at), 3), "frames_generated": self.frames_generated, "clients_connected": self.clients_connected, "last_frame_age_ms": round(max(0.0, now-self.last_frame_at)*1000, 1) if self.last_frame_at is not None else None}
