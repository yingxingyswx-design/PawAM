"""Probe Apple's Widevine licence endpoint to learn the accepted request shape.

We already know from the playback lease response:
  hls-key-server-url = https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense
  widevine-cert-url  = https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/widevineCert

The manifest's #EXT-X-KEY carries a data: URI whose payload is the 16-byte key id, and Apple
expects that URI echoed back. This script tries the plausible request bodies and prints the
server's answer, which is far more informative than guessing offline.
"""
import base64
import json
import os
import subprocess
import sys

TOKEN_FILE = os.path.join(os.environ.get("TEMP", "."), "am_token.txt")
USER_TOKEN_FILE = os.path.join(os.environ.get("TEMP", "."), "am_usertoken.txt")

LICENCE_URL = (
    "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
)

# Captured from a live lease on the device.
KEY_URI = "data:;base64,AAAAAA0ihC8AHaY3IAAvhg=="
ADAM_ID = "220365871"


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read().strip()


def post(url, headers, body):
    cmd = ["curl", "-s", "-o", "-", "-w", "\n__HTTP__%{http_code}", "--max-time", "30", "-X", "POST"]
    cmd.append(url)
    for key, value in headers.items():
        cmd += ["-H", f"{key}: {value}"]
    cmd += ["--data-binary", body]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    text = out.stdout or ""
    code = ""
    if "__HTTP__" in text:
        text, _, code = text.rpartition("__HTTP__")
    return code.strip(), text.strip()


def main() -> int:
    token = read(TOKEN_FILE)
    user_token = read(USER_TOKEN_FILE)

    headers = {
        "Authorization": f"Bearer {token}",
        "Media-User-Token": user_token,
        "Origin": "https://music.apple.com",
        "User-Agent": (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        ),
        "Content-Type": "application/json",
    }

    # A syntactically valid but meaningless challenge: the server validates structure first, so a
    # rejection message still tells us which fields it expects.
    fake_challenge = base64.b64encode(b"\x08\x04" + b"\x00" * 32).decode()

    attempts = {
        "minimal": {
            "challenge": fake_challenge,
            "key-system": "com.widevine.alpha",
            "uri": KEY_URI,
            "adamId": ADAM_ID,
            "isLibrary": False,
            "user-initiated": True,
        },
        "no-key-system": {
            "challenge": fake_challenge,
            "uri": KEY_URI,
            "adamId": ADAM_ID,
        },
    }

    for name, payload in attempts.items():
        body = json.dumps(payload)
        code, text = post(LICENCE_URL, headers, body)
        print(f"== {name}: http={code} len={len(text)}")
        print(text[:600])
        print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
