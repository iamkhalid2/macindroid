"""Protocol test for the Mac app: pretends to be the paired phone and checks what the Mac does.

Run:  uv run --with cryptography python3 test_client.py [--clipboard]

The Mac keeps one phone link and the newest wins, so the real phone gets bumped while this runs and will
bump the test back within a second or two. Turn the phone's Wi-Fi off first for a clean run.
--clipboard also checks clipboard sync both ways; it overwrites the Mac clipboard (text is restored, other
content such as images is not).
"""
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SUPPORT = Path.home() / "Library/Application Support/Macindroid"
KEY = base64.b64decode((SUPPORT / "key").read_text().strip())
DOWNLOADS = Path.home() / "Downloads"
aes = AESGCM(KEY)
TEXT, IMAGE, HELLO, PING, PONG = 1, 2, 3, 4, 5
FILE_OFFER, FILE_CHUNK, FILE_END = 20, 21, 22
STATUS = 32
PHOTO, PHOTO_GET, PHOTO_FULL = 36, 37, 38
failures = 0


def frame(kind: int, payload: bytes, key: AESGCM = aes) -> bytes:
    nonce = os.urandom(12)
    sealed = nonce + key.encrypt(nonce, bytes([kind]) + payload, None)
    return struct.pack(">I", len(sealed)) + sealed


def read_frame(sock: socket.socket) -> tuple[int, bytes]:
    def exactly(n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        return buf

    (length,) = struct.unpack(">I", exactly(4))
    data = exactly(length)
    plain = aes.decrypt(data[:12], data[12:], None)
    return plain[0], plain[1:]


def read_kind(sock: socket.socket, wanted: int) -> bytes:
    """Next frame of the wanted kind, skipping unrelated traffic (pings, clipboard)."""
    while True:
        kind, payload = read_frame(sock)
        if kind == wanted:
            return payload


def check(name: str, ok: bool) -> None:
    global failures
    failures += not ok
    print(("PASS " if ok else "FAIL ") + name)


def send_file(sock: socket.socket, name: str, data: bytes, digest: bytes) -> None:
    file_id = int.from_bytes(os.urandom(8), "big")
    head = file_id.to_bytes(8, "big")
    offer = {"id": str(file_id), "name": name, "size": len(data), "mime": "application/octet-stream"}
    sock.sendall(frame(FILE_OFFER, json.dumps(offer).encode()))
    for i in range(0, len(data), 256 * 1024):
        sock.sendall(frame(FILE_CHUNK, head + data[i : i + 256 * 1024]))
    sock.sendall(frame(FILE_END, head + digest))


def landed(name: str, timeout: float = 5) -> Path | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        hits = sorted(DOWNLOADS.glob(name.replace(".bin", "*.bin")))
        if hits:
            return hits[0]
        time.sleep(0.1)
    return None


phone_name = subprocess.run(["defaults", "read", "xyz.iamkhalid.macindroid", "phoneName"],
                            capture_output=True, text=True).stdout.strip() or "test phone"
sock = socket.create_connection(("127.0.0.1", 47800), timeout=5)
sock.sendall(frame(HELLO, phone_name.encode()))
check("Mac answers HELLO with its own", read_kind(sock, HELLO) != b"")
sock.sendall(frame(PING, b""))
read_kind(sock, PONG)
check("ping gets pong", True)
sock.sendall(frame(STATUS, json.dumps({"battery": 42, "charging": True, "name": phone_name}).encode()))

# phone -> Mac: a 3 MB file arrives intact in ~/Downloads
tag = os.urandom(4).hex()
data = os.urandom(3 * 1024 * 1024 + 123)
good = f"macindroid-selftest-{tag}.bin"
send_file(sock, good, data, hashlib.sha256(data).digest())
path = landed(good)
check("phone->Mac file lands in Downloads", path is not None)
if path:
    check("phone->Mac file is byte-identical (SHA-256)", hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(data).digest())
    path.unlink()

# phone -> Mac: a file whose checksum doesn't match is thrown away, partial file included
bad_name = f"macindroid-selftest-{tag}-corrupt.bin"
send_file(sock, bad_name, data[:1000], hashlib.sha256(b"something else").digest())
sock.sendall(frame(PING, b""))
read_kind(sock, PONG)  # frames are handled in order, so the corrupt file has been dealt with by now
check("corrupted file is discarded", landed(bad_name, timeout=0.5) is None)
check("no partial files left behind", not list(DOWNLOADS.glob(".macindroid-*.part")))

# phone -> Mac: a new screenshot's preview brings up the photo card (unless Show New Photos from Phone is off)
def photo_frame(kind: int, meta: dict, data: bytes) -> bytes:
    head = json.dumps(meta).encode()
    return frame(kind, struct.pack(">I", len(head)) + head + data)


LOG = Path.home() / "Library/Logs/Macindroid.log"
log_size = LOG.stat().st_size
preview = (Path(__file__).parent.parent / "docs/icon.png").read_bytes()
sock.sendall(photo_frame(PHOTO, {"id": 9, "name": "Screenshot_test.png", "screenshot": True, "width": 512,
                                 "height": 512, "size": len(preview)}, preview))
sock.sendall(photo_frame(PHOTO_FULL, {"id": 12345, "error": "gone"}, b""))  # an answer nobody asked for is ignored
sock.sendall(frame(PING, b""))
read_kind(sock, PONG)
time.sleep(0.3)
shown = "screenshot preview" in LOG.read_text(errors="replace")[log_size:]
off = subprocess.run(["defaults", "read", "xyz.iamkhalid.macindroid", "photoPreviews"], capture_output=True, text=True).stdout.strip() == "0"
check("screenshot preview shows the photo card", shown or off)

if "--clipboard" in sys.argv:
    saved = subprocess.run(["pbpaste"], capture_output=True).stdout
    sock.sendall(frame(TEXT, "hello from phone ✓".encode()))
    time.sleep(0.8)
    check("phone->Mac text lands on the Mac clipboard",
          subprocess.run(["pbpaste"], capture_output=True).stdout.decode() == "hello from phone ✓")
    subprocess.run(["pbcopy"], input="copied on mac 123".encode())
    check("Mac->phone text arrives", read_kind(sock, TEXT) == b"copied on mac 123")
    subprocess.run(["pbcopy"], input=saved)
    print("clipboard text restored")

# a connection with the wrong key is dropped
bad = socket.create_connection(("127.0.0.1", 47800), timeout=5)
bad.sendall(frame(HELLO, b"intruder", AESGCM(os.urandom(32))))
time.sleep(0.5)
check("wrong key is disconnected", bad.recv(1) == b"")

sock.close()
print("all passed" if failures == 0 else f"{failures} failed")
sys.exit(1 if failures else 0)
