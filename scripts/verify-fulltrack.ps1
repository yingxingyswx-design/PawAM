# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Verify Apple Music full-track playback by resolving every tap from the view hierarchy.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$tmp = Join-Path $env:TEMP "pawam_ui3.xml"

function Dump-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_ui3.xml" 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/pawam_ui3.xml $tmp 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
}

function Tap-Text([string]$label) {
    $xml = Dump-Ui
    foreach ($n in [regex]::Matches($xml, '<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        if ($n.Groups[1].Value -eq $label) {
            $x = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
            $y = ([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2
            Write-Host ("  TAP '{0}' ({1},{2})" -f $label, [int]$x, [int]$y)
            & $adb -s $serial shell "input tap $([int]$x) $([int]$y)" | Out-Null
            Start-Sleep -Seconds 3
            return $true
        }
    }
    Write-Host "  MISS '$label'"
    return $false
}

Write-Host "== start"
& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -S -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 8

Write-Host "== provider"
Tap-Text "切换提供商" | Out-Null
Tap-Text "Apple Music" | Out-Null
Start-Sleep -Seconds 5

Write-Host "== library"
Tap-Text "库" | Out-Null
Start-Sleep -Seconds 4
Tap-Text "爱爱爱" | Out-Null
Start-Sleep -Seconds 6

Write-Host "== play"
Tap-Text "苏丽珍" | Out-Null
Start-Sleep -Seconds 20

$appPid = (& $adb -s $serial shell "pidof dev.pawam.twelve.dev") -join ""
Write-Host "== drm log"
& $adb -s $serial logcat -d --pid=$appPid | Select-String -Pattern "AppleMusicDrm|Provisioning|Licence HTTP|DRM session error|Source error" | Select-Object -Last 8
Write-Host "== state"
& $adb -s $serial shell "dumpsys media_session | grep -A8 'pkg=dev.pawam' | grep state=PlaybackState | head -1"
