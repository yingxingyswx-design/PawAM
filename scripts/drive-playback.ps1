# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Drive PawAM on-device by reading the view hierarchy before every tap, instead of
# guessing coordinates. Prints the resolved bounds so a mismatch is obvious.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$tmp = Join-Path $env:TEMP "pawam_ui.xml"

function Dump-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_ui.xml" 2>&1 | Out-Null
    & $adb -s $serial pull /sdcard/pawam_ui.xml $tmp 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
}

function Find-Center([string]$xml, [string]$label) {
    $m = [regex]::Matches($xml, '<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($n in $m) {
        if ($n.Groups[1].Value -eq $label) {
            $x = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
            $y = ([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2
            return @{ X = [int]$x; Y = [int]$y; Bounds = $n.Groups[2].Value + "," + $n.Groups[3].Value + " " + $n.Groups[4].Value + "," + $n.Groups[5].Value }
        }
    }
    return $null
}

function Tap-Label([string]$label) {
    $xml = Dump-Ui
    $hit = Find-Center $xml $label
    if ($null -eq $hit) {
        Write-Host "  MISS: '$label' not on screen"
        return $false
    }
    Write-Host ("  TAP '{0}' at ({1},{2}) bounds={3}" -f $label, $hit.X, $hit.Y, $hit.Bounds)
    & $adb -s $serial shell "input tap $($hit.X) $($hit.Y)" | Out-Null
    Start-Sleep -Seconds 2
    return $true
}

function Show-Playback([string]$tag) {
    $out = & $adb -s $serial shell "dumpsys media_session | grep -A8 'pkg=dev.pawam' | grep state=PlaybackState | head -1"
    $line = ($out -join " ").Trim()
    if ($line.Length -gt 200) { $line = $line.Substring(0, 200) }
    Write-Host "  [$tag] $line"
}

Write-Host "== restart app"
& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -S -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 8

Write-Host "== switch provider"
if (-not (Tap-Label "切换提供商")) { exit 1 }
if (-not (Tap-Label "Henry唔系豹")) { exit 1 }
Start-Sleep -Seconds 5

Write-Host "== open library"
Tap-Label "库" | Out-Null
Start-Sleep -Seconds 4

Write-Host "== open playlist"
Tap-Label "播放列表" | Out-Null
Start-Sleep -Seconds 4
$xml = Dump-Ui
$row = Find-Center $xml "Henry唔系豹喜欢的音乐"
if ($null -eq $row) {
    Write-Host "  playlist row not found; dumping visible text:"
    [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ } | Select-Object -Unique -First 15 | ForEach-Object { "    $_" }
    exit 1
}
Write-Host ("  TAP playlist at ({0},{1})" -f $row.X, $row.Y)
& $adb -s $serial shell "input tap $($row.X) $($row.Y)" | Out-Null
Start-Sleep -Seconds 10

$appPid = (& $adb -s $serial shell "pidof dev.pawam.twelve.dev") -join ""
Write-Host "== playlist load log"
& $adb -s $serial logcat -d --pid=$appPid | Select-String -Pattern "NetEaseDataSource|playlist .*tracks" | Select-Object -Last 3

Write-Host "== play first track"
Tap-Label "QZKago Requiem" | Out-Null
Start-Sleep -Seconds 12

Write-Host "== result"
& $adb -s $serial logcat -d --pid=$appPid | Select-String -Pattern "PawAMPlayback|NullPointerException|PlayerError|Source error" | Select-Object -Last 5
Show-Playback "after play"
