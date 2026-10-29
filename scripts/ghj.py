"""Small helper: run `gh api <endpoint>` with --paginate if requested and print selected JSON.

Usage:
  python ghj.py <endpoint> [--paginate] [--keys a,b,c]

Avoids PowerShell quoting problems with `gh --jq`.
"""
import json
import subprocess
import sys


def main() -> int:
    endpoint = sys.argv[1]
    paginate = "--paginate" in sys.argv
    keys = None
    if "--keys" in sys.argv:
        keys = sys.argv[sys.argv.index("--keys") + 1].split(",")

    cmd = ["gh", "api", endpoint]
    if paginate:
        cmd.append("--paginate")
        cmd += ["-H", "Accept: application/vnd.github+json"]
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if res.returncode != 0:
        sys.stderr.write(res.stderr)
        return res.returncode

    text = res.stdout.strip()
    if not text:
        print("[]")
        return 0
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        print(text)
        return 0

    items = data if isinstance(data, list) else [data]
    if keys is None:
        print(json.dumps(items, ensure_ascii=False, indent=1)[:20000])
        return 0

    def get(obj, key):
        cur = obj
        for part in key.split("."):
            if isinstance(cur, dict):
                cur = cur.get(part)
            else:
                cur = None
            if cur is None:
                return None
        return cur

    for it in items:
        print(" | ".join(str(get(it, k)) for k in keys))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
