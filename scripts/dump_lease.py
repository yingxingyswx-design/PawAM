"""Extract the Apple Music playback-lease JSON that AppleMusicPlayback logged.

Logcat splits the pretty-printed JSON across lines, each prefixed with the log header. This
reassembles them and prints the set of keys plus any URL-ish values, which is what we need to
locate the Widevine licence endpoint.
"""
import json
import re
import subprocess
import sys

ADB = r"C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "c412785e"

def main() -> int:
    out = subprocess.run(
        [ADB, "-s", SERIAL, "logcat", "-d"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout

    capturing = False
    body_lines = []
    manifest_lines = []

    for raw in out.splitlines():
        marker = "AppleMusicLease: "
        idx = raw.find(marker)
        if idx < 0:
            continue
        text = raw[idx + len(marker):]

        if "LEASE BODY:" in text:
            capturing = True
            body_lines.append(text.split("LEASE BODY:", 1)[1].strip())
            continue
        if "MANIFEST:" in text:
            capturing = False
            manifest_lines.append(text.split("MANIFEST:", 1)[1].strip())
            continue
        if capturing:
            body_lines.append(text)

    body = "\n".join(body_lines)
    print(f"lease body chars: {len(body)}")

    if body:
        try:
            doc = json.loads(body)
        except json.JSONDecodeError as exc:
            print(f"parse failed: {exc}")
            print(body[:2000])
            return 1

        def walk(node, path=""):
            if isinstance(node, dict):
                for k, v in node.items():
                    yield from walk(v, f"{path}.{k}" if path else k)
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    yield from walk(v, f"{path}[{i}]")
            else:
                yield path, node

        print("\n== all keys and values (truncated) ==")
        for path, value in walk(doc):
            s = str(value)
            if len(s) > 160:
                s = s[:160] + "..."
            print(f"  {path} = {s}")

    if manifest_lines:
        manifest = "|".join(manifest_lines)
        print(f"\nmanifest chars: {len(manifest)}")
        keys = re.findall(r"#EXT-X-KEY:[^\n|]+", manifest)
        for k in keys:
            print(f"  KEY: {k}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
