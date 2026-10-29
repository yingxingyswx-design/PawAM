"""Probe Apple's Widevine provisioning endpoint with a REAL provision request.

The request was captured from the device's CDM (logged as PROVISION_REQUEST_B64). Sending the
genuine payload from the PC lets us inspect Apple's reply in several encodings without rebuilding
and reinstalling the app each time.

The goal is to learn the exact response envelope the CDM accepts: we already know the CDM rejects
the raw protobuf ("invalid JSON/base64") and a JSON wrapper around it ("cannot locate start
substring").
"""
import base64
import json
import os
import subprocess
import sys

TEMP = os.environ.get("TEMP", ".")
REQ_FILE = os.path.join(TEMP, "provision_req.txt")
TOKEN_FILE = os.path.join(TEMP, "am_token.txt")
USER_TOKEN_FILE = os.path.join(TEMP, "am_usertoken.txt")

PROVISION_URL = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/widevineCert"


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read().strip()


def post(url, headers, body_bytes, label):
    cmd = ["curl", "-s", "-o", "-", "-w", "\n__HTTP__%{http_code}", "--max-time", "30", "-X", "POST", url]
    for key, value in headers.items():
        cmd += ["-H", f"{key}: {value}"]
    path = os.path.join(TEMP, f"prov_{label}.bin")
    cmd += ["--data-binary", "@-"]

    result = subprocess.run(
        cmd, input=body_bytes, capture_output=True, encoding=None
    )
    out = result.stdout or b""
    code = b""
    if b"__HTTP__" in out:
        out, _, code = out.rpartition(b"__HTTP__")

    with open(path, "wb") as handle:
        handle.write(out)

    head = out[:24]
    printable = out[:200].decode("utf-8", errors="replace")
    print(f"== {label}: http={code.decode().strip()} size={len(out)}")
    print(f"   head_hex={head.hex()}")
    if printable.lstrip().startswith(("{", "<")):
        print(f"   text={printable[:200]}")
    print()


def main() -> int:
    request = base64.b64decode(read(REQ_FILE))
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
    }

    print(f"provision request bytes: {len(request)}")

    # 1. raw protobuf
    post(PROVISION_URL, {**headers, "Content-Type": "application/octet-stream"}, request, "raw")

    # 2. JSON signed_request (base64)
    body = json.dumps({"signed_request": base64.b64encode(request).decode()}).encode()
    post(PROVISION_URL, {**headers, "Content-Type": "application/json"}, body, "json")

    # 3. form signed_request
    body = ("signed_request=" + base64.b64encode(request).decode()).encode()
    post(
        PROVISION_URL,
        {**headers, "Content-Type": "application/x-www-form-urlencoded"},
        body,
        "form",
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
