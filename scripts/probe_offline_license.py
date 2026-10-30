"""Probe whether Apple's licence endpoint issues a PERSISTENT (offline) Widevine licence.

Offline download only makes sense if the licence survives app restarts and network loss. Widevine
distinguishes a streaming licence from a persistent one by the CDM's key request type, which is not
visible in the request body from the app side. The practical way to find out is to send a real
challenge with the fields Apple's web player uses and inspect the response.

This script asks a single question: does the licence endpoint accept a request that is meant to be
persistent, or does it only ever issue streaming licences?
"""
import base64
import json
import os
import subprocess
import sys

TEMP = os.environ.get("TEMP", ".")
LICENCE_URL = (
    "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
)


def read(name):
    with open(os.path.join(TEMP, name), "r", encoding="utf-8") as handle:
        return handle.read().strip()


def main() -> int:
    token = read("am_token.txt")
    user_token = read("am_usertoken.txt")

    # The lease is needed for the key URI; reuse the one the device already logged.
    lease_key_uri = os.path.join(TEMP, "lease_key_uri.txt")
    if not os.path.exists(lease_key_uri):
        print("missing lease_key_uri.txt - capture it from the device log first")
        return 1

    key_uri = read("lease_key_uri.txt")

    headers = {
        "Authorization": f"Bearer {token}",
        "Media-User-Token": user_token,
        "Origin": "https://music.apple.com",
        "Content-Type": "application/json",
    }

    # A deliberately invalid challenge: the server validates the envelope before the CDM would,
    # so a change in error code tells us whether the extra fields were accepted.
    fake_challenge = base64.b64encode(b"\x08\x04" + b"\x00" * 32).decode()

    base = {
        "challenge": fake_challenge,
        "key-system": "com.widevine.alpha",
        "uri": key_uri,
    }

    variants = {
        "streaming": {**base, "adamId": "220365871", "isLibrary": False, "user-initiated": True},
        "persistent-flag": {
            **base,
            "adamId": "220365871",
            "isLibrary": False,
            "user-initiated": True,
            "persistent": True,
        },
        "persistent-key-type": {
            **base,
            "adamId": "220365871",
            "isLibrary": False,
            "user-initiated": True,
            "licenseType": "persistent",
        },
    }

    for name, payload in variants.items():
        cmd = [
            "curl", "-s", "-o", "-", "-w", "\n__H__%{http_code}", "--max-time", "25",
            "-X", "POST", LICENCE_URL,
        ]
        for key, value in headers.items():
            cmd += ["-H", f"{key}: {value}"]
        cmd += ["--data-binary", json.dumps(payload)]

        result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        out = result.stdout or ""
        code = ""
        if "__H__" in out:
            out, _, code = out.rpartition("__H__")

        print(f"== {name}: http={code.strip()} len={len(out.strip())}")
        print(f"   {out.strip()[:300]}")
        print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
