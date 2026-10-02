#!/usr/bin/env python3
"""Private intake for named DiPlay steering profiles. No uploaded content is served or executed."""
import hashlib
import json
import os
import re
import threading
import time
import unicodedata
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

BASE_PATH = "/diplay-profiles"
MAX_BYTES = 32 * 1024
OPERATIONS = {"play_pause", "next", "previous", "siri"}
PROFILE_FIELDS = {
    "schemaVersion", "backend", "carModel", "headUnitModel", "manufacturer",
    "firmware", "androidVersion", "savedAt", "bindings",
}
BINDING_FIELDS = {"operation", "keyCode", "event", "source", "logTag", "broadcastAction", "keyExtra", "eventExtra", "logContains"}


def profile_part(value):
    value = unicodedata.normalize("NFKC", value).strip()
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f-\x9f]', "_", value).strip(". ") or "unknown"
    result = ""
    for letter in value:
        if len((result + letter).encode("utf-8")) > 64:
            break
        result += letter
    return result


def validate_profile(profile):
    if not isinstance(profile, dict) or set(profile) != PROFILE_FIELDS:
        raise ValueError("Invalid profile fields")
    if type(profile["schemaVersion"]) is not int or profile["schemaVersion"] != 1 or profile["backend"] != "system_key_events":
        raise ValueError("Unsupported profile format")
    for field in ("carModel", "headUnitModel", "manufacturer", "firmware", "androidVersion"):
        value = profile[field]
        if not isinstance(value, str) or len(value) > 120 or any(unicodedata.category(c) == "Cc" for c in value):
            raise ValueError("Invalid head unit information")
    if not profile["carModel"].strip() or not profile["headUnitModel"].strip():
        raise ValueError("Vehicle and head unit model required")
    if type(profile["savedAt"]) is not int or not 0 < profile["savedAt"] < 10**15:
        raise ValueError("Invalid save time")
    bindings = profile["bindings"]
    if not isinstance(bindings, list) or not 1 <= len(bindings) <= 4:
        raise ValueError("At least one steering button required")
    operations, inputs = set(), set()
    for binding in bindings:
        if not isinstance(binding, dict) or set(binding) != BINDING_FIELDS:
            raise ValueError("Invalid button fields")
        operation, code = binding["operation"], binding["keyCode"]
        if not isinstance(operation, str) or operation not in OPERATIONS or operation in operations:
            raise ValueError("Invalid or repeated button operation")
        if type(code) is not int or not 0 <= code <= 1_000_000:
            raise ValueError("Invalid key code")
        if type(binding["event"]) is not int or not 0 <= binding["event"] <= 4:
            raise ValueError("Invalid button event")
        if binding["source"] not in ("broadcast", "logcat"):
            raise ValueError("Unsupported input source")
        tag = binding["logTag"]
        if not isinstance(tag, str) or len(tag) > 100 or any(unicodedata.category(c) == "Cc" for c in tag):
            raise ValueError("Invalid log source")
        if binding["source"] == "logcat" and not tag.strip():
            raise ValueError("Log source required")
        for field, limit in (("broadcastAction", 160), ("keyExtra", 120), ("eventExtra", 120)):
            value = binding[field]
            if not isinstance(value, str) or len(value) > limit or any(unicodedata.category(c) == "Cc" for c in value):
                raise ValueError("Invalid broadcast input")
        if binding["source"] == "broadcast" and (not re.fullmatch(r"[A-Za-z0-9_.]+", binding["broadcastAction"]) or not binding["keyExtra"].strip()):
            raise ValueError("Broadcast action and key field required")
        fragments = binding["logContains"]
        if not isinstance(fragments, list) or len(fragments) > 4 or (binding["source"] != "logcat" and fragments):
            raise ValueError("Invalid log fragments")
        for fragment in fragments:
            if not isinstance(fragment, str) or not fragment.strip() or len(fragment) > 160 or any(unicodedata.category(c) == "Cc" for c in fragment):
                raise ValueError("Invalid log fragment")
        if code == 0 and not (binding["source"] == "logcat" and fragments):
            raise ValueError("A key code or explicit log rule is required")
        input_id = (binding["source"], code, tag, binding["broadcastAction"], binding["keyExtra"], binding["eventExtra"], tuple(sorted(fragments)))
        if input_id in inputs:
            raise ValueError("Repeated button input")
        operations.add(operation)
        inputs.add(input_id)
    return profile


class ProfileStore:
    def __init__(self, root):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.Lock()
        self.requests = defaultdict(deque)
        self.used_bytes = sum(file.stat().st_size for file in self.root.rglob("*.json") if file.is_file())
        self.max_bytes = int(os.environ.get("DIPLAY_PROFILES_MAX_STORAGE", str(100 * 1024 * 1024)))

    def admit(self, remote):
        current = time.monotonic()
        with self.lock:
            if len(self.requests) >= 4096:
                self.requests = defaultdict(deque, {ip: times for ip, times in self.requests.items() if times and times[-1] > current - 3600})
                if len(self.requests) >= 4096 and remote not in self.requests:
                    return False
            times = self.requests[remote]
            while times and times[0] < current - 3600:
                times.popleft()
            if len(times) >= 60:
                return False
            times.append(current)
            return True

    def save(self, content):
        profile = validate_profile(json.loads(content.decode("utf-8")))
        receipt = hashlib.sha256(content).hexdigest()
        car, unit, firmware = (profile_part(profile[field]) for field in ("carModel", "headUnitModel", "firmware"))
        name = f"{car}_{unit}_{firmware}.json"
        directory = self.root / car / unit / receipt
        destination = directory / name
        with self.lock:
            if destination.is_file():
                return receipt, name
            if self.used_bytes + len(content) > self.max_bytes:
                raise OSError("Profile storage is full")
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = directory / (name + ".tmp")
            with temporary.open("wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(destination)
            destination.chmod(0o600)
            self.used_bytes += len(content)
        return receipt, name


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if urlsplit(self.path).path == BASE_PATH + "/health":
            self.reply(200, {"ok": True, "schemaVersion": 1})
        else:
            self.reply(404, {"ok": False, "error": "Not found"})

    def do_POST(self):
        if urlsplit(self.path).path != BASE_PATH + "/v1/profiles":
            self.reply(404, {"ok": False, "error": "Not found"})
            return
        remote = self.headers.get("X-Real-IP", self.client_address[0])[:100]
        if not self.server.store.admit(remote):
            self.reply(429, {"ok": False, "error": "Try again later"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > MAX_BYTES:
                self.reply(413, {"ok": False, "error": "Profile too large"})
                return
            if length <= 0 or self.headers.get("Transfer-Encoding") or self.headers.get_content_type() != "application/json":
                raise ValueError("JSON profile required")
            content = self.rfile.read(length)
            if len(content) != length:
                raise ValueError("Incomplete profile")
            receipt, name = self.server.store.save(content)
            self.reply(201, {"ok": True, "receipt": receipt, "fileName": name})
        except (ValueError, UnicodeError, TypeError, KeyError, RecursionError):
            self.reply(422, {"ok": False, "error": "Invalid steering profile"})
        except OSError:
            self.reply(503, {"ok": False, "error": "Storage temporarily unavailable"})


def main():
    os.umask(0o077)
    root = Path(os.environ.get("DIPLAY_PROFILES_DATA", str(Path(__file__).parent / "data" / "profiles")))
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("DIPLAY_PROFILES_PORT", "18794"))), Handler)
    server.daemon_threads = True
    server.store = ProfileStore(root)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
