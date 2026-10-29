# Reading and writing source files safely on Windows

PowerShell's `Get-Content` / `Set-Content` default to the system ANSI code page (GBK on a
zh-CN Windows install), **not UTF-8**. Using them on this repository silently corrupts every
non-ASCII character and can break XML/HTML well-formedness.

This actually happened once: `res/values/strings.xml` was round-tripped through
`Get-Content -Raw` / `Set-Content` and came back with `…` turned into `鈥?` and a `<` eaten, which
made the resource file unparseable.

## Rules

1. Always pass `-Encoding utf8` to `Get-Content` and `Set-Content`.
2. Prefer the editor tools (`read` / `write` / `edit`) for source files — they are UTF-8 correct.
3. Never round-trip a file through PowerShell just to make one textual change.
4. After touching `res/values/*.xml`, re-parse it:

```powershell
$raw = Get-Content path\to\file.xml -Raw -Encoding utf8
[xml]$doc = $raw   # throws if malformed
```

5. To detect damage, look for U+FFFD and known mojibake sequences:

```powershell
$t = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($f))
if ($t.Contains([char]0xFFFD)) { "broken: $f" }
```

## Restoring a corrupted upstream file

PawAM keeps a pristine copy of the upstream tree in `upstream/twelve` (git-ignored). Restore the
file from there and re-apply local edits with the editor tools:

```powershell
Copy-Item upstream\twelve\app\src\main\res\values\strings.xml `
          app-src\app\src\main\res\values\strings.xml -Force
```
