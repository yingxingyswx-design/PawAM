# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Verifies that typing in the library's own search field filters in place and does NOT open the
# global search screen. The global screen is recognised by its own search view being shown, which
# replaces the whole main content area.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$dump = Join-Path $env:TEMP "pawam_libsearch.xml"

function Get-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_libsearch.xml" 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/pawam_libsearch.xml $dump 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($dump, [System.Text.Encoding]::UTF8)
}

function Get-Labels([string]$xml) {
    return [regex]::Matches($xml, 'text="([^"]+)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ } |
        Select-Object -Unique
}

function Tap-Center([string]$xml, [string]$label) {
    $m = [regex]::Matches(
        $xml,
        '<node[^>]*text="' + [regex]::Escape($label) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    ) | Select-Object -First 1

    if (-not $m) {
        return $false
    }

    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    & $adb -s $serial shell "input tap $([int]$x) $([int]$y)" | Out-Null
    return $true
}

& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 11

Write-Host "== library tab"
$xml = Get-Ui
Tap-Center $xml "库" | Out-Null
Start-Sleep -Seconds 5

$xml = Get-Ui
$labels = Get-Labels $xml
Write-Host "  labels: $($labels -join ' / ')"

$hasField = $labels | Where-Object { $_ -match '搜索库内' }
if (-not $hasField) {
    Write-Host "  library search field not found"
    exit 1
}

Write-Host "== focus field and type"
$m = [regex]::Matches($xml, '<node[^>]*class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') |
    Select-Object -First 1
if (-not $m) {
    Write-Host "  no EditText"
    exit 1
}

$x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
$y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
& $adb -s $serial shell "input tap $([int]$x) $([int]$y)" | Out-Null
Start-Sleep -Seconds 2
& $adb -s $serial shell "input text 'love'" | Out-Null
Start-Sleep -Seconds 9

$xml = Get-Ui
$labels = Get-Labels $xml

Write-Host "== after typing"
Write-Host "  labels: $($labels -join ' / ')"

# The global search screen hides the tab bar entirely; the library screen keeps it.
$stillInLibrary = ($labels -contains '专辑') -and ($labels -contains '艺术家')

if ($stillInLibrary) {
    Write-Host "  RESULT: stayed in the library screen (tabs still visible)"
} else {
    Write-Host "  RESULT: left the library screen"
}

Write-Host "== requests"
& $adb -s $serial logcat -d |
    Select-String -Pattern "me/library/search" |
    Select-Object -Last 3 |
    ForEach-Object { $_.Line -replace '^\S+\s+\S+\s+\d+\s+\d+\s+\w\s+', '' }
