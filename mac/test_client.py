"""Protocol test for the Mac app: pretends to be the paired phone and checks what the Mac does.

Run:  uv run --with cryptography python3 test_client.py [--clipboard]

The Mac keeps one phone link, so the real phone is off it while this runs (about a minute) and reconnects by
itself afterwards. The test switches on the app's debug setting for that and switches it off at the end.
It puts a few "Macindroid self-test" notifications in Notification Centre and takes them out again.
--clipboard also checks clipboard sync both ways; it overwrites the Mac clipboard (text is restored, other
content such as images is not).
"""
import atexit
import base64
import hashlib
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
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
UPDATE_OFFER, UPDATE_GET, UPDATE_APK = 42, 43, 44
NOTIF_POST, NOTIF_REMOVE, NOTIF_DISMISS, NOTIF_SYNC = 10, 11, 14, 16
SLEEP, KNOCK = 45, 46
CALL, CALL_ACTION, DIAL, FS_REQUEST, FS_REPLY = 47, 48, 49, 50, 51
DOMAIN = "xyz.iamkhalid.macindroid"
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


def default(name: str) -> str:
    return subprocess.run(["defaults", "read", DOMAIN, name], capture_output=True, text=True).stdout.strip()


def connect() -> socket.socket:
    s = socket.create_connection(("127.0.0.1", 47800), timeout=5)
    s.sendall(frame(HELLO, phone_name.encode()))
    return s


def expect(s: socket.socket, wanted: int, timeout: float) -> bytes | None:
    """The next frame of the wanted kind within the timeout, else None."""
    s.settimeout(timeout)
    try:
        return read_kind(s, wanted)
    except (TimeoutError, ConnectionError):
        return None
    finally:
        s.settimeout(5)


phone_name = default("phoneName") or "test phone"
phone_host = default("phoneHost")
lock_was = default("lockWhenAway") == "1"
subprocess.run(["defaults", "write", DOMAIN, "debugUntil", "-int", str(int(time.time()) + 240)], check=True)


@atexit.register
def leave_it_as_it_was() -> None:
    """Also when a check throws: the Mac is listening again and the real phone can reconnect."""
    subprocess.run(["open", "-g", "macindroid://debug-unmount"])
    subprocess.run(["open", "-g", "macindroid://debug-wake"])
    time.sleep(0.5)
    if lock_was:
        subprocess.run(["defaults", "write", DOMAIN, "lockWhenAway", "-bool", "true"])
    else:
        subprocess.run(["defaults", "delete", DOMAIN, "lockWhenAway"], capture_output=True)
    subprocess.run(["defaults", "delete", DOMAIN, "debugUntil"], capture_output=True)


time.sleep(0.5)
sock = connect()
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

# a phone app older than the one bundled in the Mac app is offered it, and gets exactly the bundled APK
apk = next((a / "Contents/Resources/Macindroid.apk" for a in (Path.home() / "Applications/Macindroid.app",
            Path("/Applications/Macindroid.app")) if (a / "Contents/Resources/Macindroid.apk").exists()), None)
if apk:
    sock.sendall(frame(STATUS, json.dumps({"battery": 42, "charging": True, "name": phone_name, "version": 1, "updates": True}).encode()))
    offer = json.loads(read_kind(sock, UPDATE_OFFER))
    check("an outdated phone app is offered the update", offer.get("code", 0) > 1 and offer.get("size") == apk.stat().st_size)
    sock.sendall(frame(UPDATE_GET, b""))
    check("the update sent is the bundled APK, byte for byte", read_kind(sock, UPDATE_APK) == apk.read_bytes())

# ---- notifications stay the same on both sides (what's in Notification Centre is read back through the app's log)
def post(s: socket.socket, key: str, silent: bool = True) -> None:
    head = json.dumps({"key": key, "pkg": "selftest", "app": "Macindroid self-test", "title": "Macindroid self-test",
                       "text": key, "silent": silent}).encode()
    s.sendall(frame(NOTIF_POST, struct.pack(">I", len(head)) + head))


def up(wait: float = 1.0) -> set[str]:
    """The phone notifications macOS has in Notification Centre right now."""
    time.sleep(wait)
    mark = os.urandom(4).hex()
    subprocess.run(["open", "-g", f"macindroid://debug-list?tag={mark}"], check=True)
    deadline = time.time() + 5
    while time.time() < deadline:
        for line in LOG.read_text(errors="replace").splitlines()[-40:]:
            if f"debug {mark}: phone notifications up:" in line:
                return set(json.loads(line.split("up:", 1)[1]))
        time.sleep(0.2)
    return {"?"}


a, b, c, d = (f"selftest-{tag}-{x}" for x in "abcd")
real = up(0)  # your phone's own notifications: listed in every sync below, so the test leaves them alone
for old in [k for k in real if k.startswith("selftest-")]:  # from a run that didn't finish
    sock.sendall(frame(NOTIF_REMOVE, old.encode()))
real = [k for k in real if not k.startswith("selftest-")]
post(sock, a)
post(sock, b)
if not {a, b} <= up():
    print("SKIP notification checks: macOS isn't keeping Macindroid's notifications in Notification Centre")
else:
    sock.sendall(frame(NOTIF_REMOVE, a.encode()))
    now = up()
    check("cleared on the phone: gone from the Mac, the other one stays", a not in now and b in now)

    subprocess.run(["open", "-g", f"macindroid://debug-clear?key={b}"], check=True)  # a clear macOS doesn't report
    check("cleared on the Mac: the phone is told to clear it", expect(sock, NOTIF_DISMISS, 12) == b.encode())

    post(sock, c)
    post(sock, d)
    check("two more are up", {c, d} <= up())
    sock.sendall(frame(NOTIF_SYNC, json.dumps(real + [c]).encode()))  # as after a reconnect: the phone only has c left
    now = up()
    check("after a reconnect: what the phone no longer has is cleared on the Mac", d not in now and c in now)

    sock.close()  # the phone goes away; c is cleared on the Mac meanwhile
    time.sleep(0.5)
    subprocess.run(["open", "-g", f"macindroid://debug-clear?key={c}"], check=True)
    time.sleep(8)  # the Mac notices on its own within a few seconds
    sock = connect()
    read_kind(sock, HELLO)
    post(sock, c)  # the phone still has it, and says so
    sock.sendall(frame(NOTIF_SYNC, json.dumps(real + [c]).encode()))
    check("cleared on the Mac while apart: cleared on the phone at the reconnect", expect(sock, NOTIF_DISMISS, 8) == c.encode())
    check("and it isn't put back on the Mac", c not in up())
    sock.sendall(frame(NOTIF_SYNC, json.dumps(real).encode()))
    check("nothing from the test is left in Notification Centre", not any(k.startswith("selftest-") for k in up()))

# ---- calls: a ringing call brings up the card; its buttons reach the phone; a clicked number is dialled
def said(text: str, since: int, timeout: float = 5) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if text in LOG.read_text(errors="replace")[since:]:
            return True
        time.sleep(0.2)
    return False


APP = next(a for a in (Path.home() / "Applications/Macindroid.app", Path("/Applications/Macindroid.app")) if a.exists())
log_size = LOG.stat().st_size
ring = json.dumps({"id": "call-1", "state": "ringing", "name": "Macindroid self-test", "app": "Phone", "answer": True, "decline": True}).encode()
sock.sendall(frame(CALL, struct.pack(">I", len(ring)) + ring))
check("a ringing call shows on the Mac", said("a call is ringing", log_size))
subprocess.run(["open", "-g", "macindroid://debug-decline"], check=True)
pressed = expect(sock, CALL_ACTION, 5)
check("Decline on the Mac reaches the phone", pressed is not None and json.loads(pressed) == {"id": "call-1", "action": "decline"})
gone = json.dumps({"id": "call-1", "state": "ended"}).encode()
sock.sendall(frame(CALL, struct.pack(">I", len(gone)) + gone))
subprocess.run(["open", "-g", "-a", str(APP), "tel:+1%20(555)%20010-0199"], check=True)  # no confirmation while testing
check("a phone number clicked on the Mac is sent to the phone to dial", expect(sock, DIAL, 5) == b"+15550100199")
subprocess.run(["open", "-g", "-a", str(APP), "tel:"], check=True)
check("an empty number isn't", expect(sock, DIAL, 2) is None)


# ---- the phone in Finder: macOS mounts the volume and every file operation comes through the link to this "phone",
# which keeps its files in a temporary folder
def serve_files(s: socket.socket, root: Path, stop: threading.Event) -> None:
    uploads: dict[int, tuple] = {}

    def inside(path: str) -> Path:
        full = (root / path.lstrip("/")).resolve()
        if full != root and root not in full.parents:
            raise PermissionError
        return full

    def answer(i: int, head: dict, data: bytes = b"") -> None:
        j = json.dumps({**head, "id": i}).encode()
        s.sendall(frame(FS_REPLY, struct.pack(">I", len(j)) + j + data))

    s.settimeout(0.3)
    while not stop.is_set():
        try:
            kind, payload = read_frame(s)
        except TimeoutError:
            continue
        except (ConnectionError, OSError):
            return
        if kind == PING:
            s.sendall(frame(PONG, b""))
        if kind != FS_REQUEST:
            continue
        (n,) = struct.unpack(">I", payload[:4])
        q, data = json.loads(payload[4 : 4 + n]), payload[4 + n :]
        i = q["id"]
        try:
            op = q["op"]
            if op == "stat":
                f = inside(q["path"])
                st = f.stat()
                answer(i, {"ok": True, "d": int(f.is_dir()), "s": 0 if f.is_dir() else st.st_size, "m": int(st.st_mtime * 1000),
                           "free": 5 << 30, "total": 8 << 30})
            elif op == "list":
                d = inside(q["path"])
                answer(i, {"ok": True, "entries": [[c.name, int(c.is_dir()), 0 if c.is_dir() else c.stat().st_size,
                                                     int(c.stat().st_mtime * 1000)] for c in d.iterdir()]})
            elif op == "read":
                f = inside(q["path"])
                size = f.stat().st_size
                offset = min(q.get("offset", 0), size)
                left = size - offset if q.get("length", -1) < 0 else min(q["length"], size - offset)
                head = {"ok": True, "size": size, "m": int(f.stat().st_mtime * 1000), "offset": offset, "length": left}
                with f.open("rb") as fh:
                    fh.seek(offset)
                    while True:
                        piece = fh.read(min(256 * 1024, left))
                        left -= len(piece)
                        answer(i, {**head, "more": left > 0}, piece)
                        head = {"ok": True}
                        if left <= 0:
                            break
            elif op == "create":
                target = inside(q["path"])
                if not target.parent.is_dir():
                    raise FileNotFoundError
                temp = target.parent / f".macindroid-{i}.part"
                uploads[i] = (temp.open("wb"), temp, target)
                answer(i, {"ok": True, "handle": i})
            elif op == "append":
                uploads[q["handle"]][0].write(data)
                answer(i, {"ok": True})
            elif op == "commit":
                fh, temp, target = uploads.pop(q["handle"])
                fh.close()
                temp.replace(target)
                answer(i, {"ok": True})
            elif op == "abort":
                fh, temp, _ = uploads.pop(q["handle"])
                fh.close()
                temp.unlink()
                answer(i, {"ok": True})
            elif op == "mkdir":
                d = inside(q["path"])
                if d.exists():
                    answer(i, {"ok": False, "error": "exists"})
                else:
                    d.mkdir()
                    answer(i, {"ok": True})
            elif op == "delete":
                f = inside(q["path"])
                shutil.rmtree(f) if f.is_dir() else f.unlink()
                answer(i, {"ok": True})
            elif op == "move":
                a, b = inside(q["from"]), inside(q["to"])
                replaced = b.exists()
                a.replace(b)
                answer(i, {"ok": True, "replaced": replaced})
            else:
                answer(i, {"ok": False, "error": "unknown"})
        except FileNotFoundError:
            answer(i, {"ok": False, "error": "missing"})
        except PermissionError:
            answer(i, {"ok": False, "error": "access"})
        except Exception:
            answer(i, {"ok": False, "error": "io"})


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


store = Path(tempfile.mkdtemp(prefix="macindroid-selftest-phone-")).resolve()
(store / "DCIM").mkdir()
(store / "DCIM" / "photo one.jpg").write_bytes(os.urandom(5 * 1024 * 1024 + 17))
(store / "Notes & more.txt").write_bytes(("héllo from the phone" + chr(10)).encode())
stop = threading.Event()
server = threading.Thread(target=serve_files, args=(sock, store, stop), daemon=True)
server.start()
log_size = LOG.stat().st_size
subprocess.run(["open", "-g", "macindroid://files"], check=True)  # the real thing: it opens a Finder window too
mount = None
if said("phone files: mounted at ", log_size, 25):
    mount = Path(LOG.read_text(errors="replace")[log_size:].split("phone files: mounted at ", 1)[1].splitlines()[0].rsplit(" in ", 1)[0])
check("macOS mounts the phone as a volume", mount is not None and mount.is_dir())
if mount:
    try:
        check("the volume lists the phone's files", sorted(os.listdir(mount)) == ["DCIM", "Notes & more.txt"])
        shown = subprocess.run(["osascript", "-e", 'tell application "Finder" to get name of front window'], capture_output=True, text=True, timeout=20)
        check("Finder opens a window on it (and the app isn't stuck waiting for itself)", shown.stdout.strip() == mount.name)
        check("a text file reads back, accents and all", (mount / "Notes & more.txt").read_text() == "héllo from the phone" + chr(10))
        check("a 5 MB photo reads back byte-identical", sha(mount / "DCIM" / "photo one.jpg") == sha(store / "DCIM" / "photo one.jpg"))
        with (mount / "DCIM" / "photo one.jpg").open("rb") as fh:
            fh.seek(3_000_000)
            middle = fh.read(1000)
        check("reading from the middle of a file gives that part", middle == (store / "DCIM" / "photo one.jpg").read_bytes()[3_000_000:3_001_000])
        local = Path(tempfile.mkdtemp(prefix="macindroid-selftest-mac-")) / "from mac.bin"
        local.write_bytes(os.urandom(3 * 1024 * 1024 + 5))
        subprocess.run(["xattr", "-w", "com.macindroid.selftest", "kept on the Mac", str(local)], check=True)
        copied = subprocess.run(["cp", str(local), str(mount / "DCIM")], capture_output=True, text=True)
        check("a 3 MB file copied in arrives on the phone byte-identical",
              copied.returncode == 0 and (store / "DCIM" / "from mac.bin").exists() and sha(store / "DCIM" / "from mac.bin") == sha(local))
        (mount / "New folder").mkdir()
        check("a new folder is made on the phone", (store / "New folder").is_dir())
        os.rename(mount / "DCIM" / "from mac.bin", mount / "New folder" / "moved.bin")
        check("a file moved and renamed moves on the phone", (store / "New folder" / "moved.bin").exists() and not (store / "DCIM" / "from mac.bin").exists())
        (mount / "New folder" / "moved.bin").unlink()
        (mount / "New folder").rmdir()
        check("deleting removes them from the phone", not (store / "New folder").exists())
        (mount / "edit.txt").write_text("first")
        (mount / "edit.txt").write_text("second, longer")
        check("a file saved twice holds the second version", (store / "edit.txt").read_text() == "second, longer")
        litter = [str(f.relative_to(store)) for f in store.rglob("*") if f.name.startswith("._") or f.name == ".DS_Store" or f.name.endswith(".part")]
        check("Finder's own bookkeeping files never reach the phone", litter == [])
    except OSError as e:
        check(f"file operations on the volume ({e})", False)
    subprocess.run(["open", "-g", "macindroid://debug-unmount"], check=True)
    for _ in range(40):
        if not os.path.ismount(mount):
            break
        time.sleep(0.25)
    check("the volume goes when it's unmounted", not os.path.ismount(mount))
stop.set()
server.join()
sock.settimeout(5)
shutil.rmtree(store, ignore_errors=True)

# ---- sleep and wake: the Mac says it's going, closes, and can't be reached (so the phone can't wake it); on waking it
# listens again and knocks on the phone's door
sock.sendall(frame(PING, b""))
read_kind(sock, PONG)
subprocess.run(["open", "-g", "macindroid://debug-sleep"], check=True)
check("going to sleep: the phone is told", expect(sock, SLEEP, 5) is not None)
sock.settimeout(5)
try:
    closed = sock.recv(1) == b""
except OSError:
    closed = True
check("going to sleep: the link is closed", closed)
time.sleep(0.5)
try:
    socket.create_connection(("127.0.0.1", 47800), timeout=2).close()
    refused = False
except OSError:
    refused = True
check("asleep: nothing is listening for the phone to wake", refused)

door = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    door.bind(("127.0.0.1", 47800))
except OSError:
    door = None  # something else on this Mac has UDP port 47800 (an Android emulator forwarding it, say)
if door:
    door.settimeout(8)
    subprocess.run(["defaults", "write", DOMAIN, "phoneHost", "127.0.0.1"], check=True)
    time.sleep(0.5)
subprocess.run(["open", "-g", "macindroid://debug-wake"], check=True)
if door:
    try:
        datagram = door.recv(512)
        knocked = aes.decrypt(datagram[:12], datagram[12:], None)[:1] == bytes([KNOCK])
    except Exception:
        knocked = False
    check("awake: the Mac knocks, sealed with the pairing key", knocked)
    door.close()
    if phone_host:
        subprocess.run(["defaults", "write", DOMAIN, "phoneHost", phone_host], check=True)
    else:
        subprocess.run(["defaults", "delete", DOMAIN, "phoneHost"])
else:
    print("SKIP the knock check: UDP port 47800 is in use on this Mac")
sock = None
for _ in range(20):
    try:
        sock = connect()
        break
    except OSError:
        time.sleep(0.25)
check("awake: the phone can connect again", sock is not None and expect(sock, HELLO, 5) is not None)

# a phone that vanishes without closing is noticed when the Mac next has something for it
log_size = LOG.stat().st_size
time.sleep(6)  # the Mac only asks a phone it hasn't heard from for a few seconds
subprocess.run(["open", "-g", "macindroid://ring"], check=True)  # something to send; this "phone" never answers the ping
time.sleep(12)
check("a phone that stopped answering is dropped", "stopped answering" in LOG.read_text(errors="replace")[log_size:])
sock.close()
sock = connect()
read_kind(sock, HELLO)

# ---- lock when the phone leaves (while testing the Mac only writes that it would lock)
subprocess.run(["defaults", "write", DOMAIN, "lockWhenAway", "-bool", "true"], check=True)
time.sleep(0.5)
sock.close()
sock = connect()  # connecting arms it
read_kind(sock, HELLO)
log_size = LOG.stat().st_size
sock.close()  # the phone blinks out and is back within the grace period
time.sleep(1.5)
sock = connect()
read_kind(sock, HELLO)
time.sleep(6)
check("a phone that's gone for a moment doesn't lock the Mac", "would lock" not in LOG.read_text(errors="replace")[log_size:])
log_size = LOG.stat().st_size  # now it stops answering, like a phone carried out of Wi-Fi range
check("a phone that leaves locks the Mac", said("would lock the Mac now", log_size, 35))
sock.close()
log_size = LOG.stat().st_size
time.sleep(6)
check("and it locks once, not again while the phone stays away", "would lock" not in LOG.read_text(errors="replace")[log_size:])
subprocess.run(["defaults", "delete", DOMAIN, "lockWhenAway"], capture_output=True)
sock = connect()
read_kind(sock, HELLO)

# a connection with the wrong key is dropped
bad = socket.create_connection(("127.0.0.1", 47800), timeout=5)
bad.sendall(frame(HELLO, b"intruder", AESGCM(os.urandom(32))))
time.sleep(0.5)
check("wrong key is disconnected", bad.recv(1) == b"")

sock.close()
print("all passed" if failures == 0 else f"{failures} failed")
sys.exit(1 if failures else 0)
