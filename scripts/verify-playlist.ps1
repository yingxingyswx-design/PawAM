# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Verify that a track inside an Apple Music playlist actually plays.
# Every tap is resolved from the view hierarchy; nothing is guessed.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$tmp = Join-Path $env:TEMP "pawam_pl.xml"

function Dump-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_pl.xml" 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/pawam_pl.xml $tmp 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
}

function Find-Node([string]$xml, [string]$label) {
    foreach ($n in [regex]::Matches($xml, '<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        if ($n.Groups[1].Value -eq $label) {
            return @{
                X = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
                Y = ([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2
            }
        }
    }
    return $null
}

function Tap-Node($node, [string]$label) {
    if ($null -eq $node) {
        Write-Host "  MISS '$label'"
        return $false
    }
    & $adb -s $serial shell "input tap $([int]$node.X) $([int]$node.Y)" | Out-Null
    Start-Sleep -Seconds 3
    return $true
}

& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -S -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 9

Write-Host "== 库"
Tap-Node (Find-Node (Dump-Ui) "库") "库" | Out-Null
Start-Sleep -Seconds 4

Write-Host "== 播放列表"
Tap-Node (Find-Node (Dump-Ui) "播放列表") "播放列表" | Out-Null
Start-Sleep -Seconds 5

Write-Host "== Dreamy"
Tap-Node (Find-Node (Dump-Ui) "Dreamy") "Dreamy" | Out-Null
Start-Sleep -Seconds 8

$xml = Dump-Ui
$track = Find-Node $xml "this is what space feels like"
Write-Host "== first track"
Tap-Node $track "this is what space feels like" | Out-Null
Start-Sleep -Seconds 22

Write-Host "== state"
& $adb -s $serial shell "dumpsys media_session | grep -A12 'package=dev.pawam.twelve.dev' | grep -m1 -E 'state=|metadata:'"
& $adb -s $serial shell "dumpsys media_session | grep -A14 'package=dev.pawam.twelve.dev' | grep -m1 'metadata:'"

Write-Host "== drm"
& $adb -s $serial logcat -d | Select-String -Pattern "Licence HTTP|Skipping unplayable|Source error|PawAMPlayback" | Select-Object -Last 5
