# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Baseline check: does playback still work from an album?
# This runs before any playlist test so a regression in the shared playAudio path is caught first.

$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\ui-driver.ps1"

$adb = $script:Adb
$serial = $script:Serial

Write-Host "== restart"
& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -S -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 9

Write-Host "== main screen"
if (-not (Set-MainScreen)) { Write-Host "  unreachable"; exit 1 }

Write-Host "== library"
Tap-Label "库" | Out-Null
Start-Sleep -Seconds 4

Write-Host "== albums tab"
Tap-Label "专辑" | Out-Null
Start-Sleep -Seconds 4

Write-Host "== open album"
if (-not (Tap-Label "爱爱爱")) {
    Write-Host "  album not listed; dumping visible labels"
    (Get-Nodes (Get-UiXml) | Where-Object { $_.Text } | Select-Object -First 12 -ExpandProperty Text) -join ", "
    exit 1
}
Start-Sleep -Seconds 6

Write-Host "== play first track"
if (-not (Tap-Label "苏丽珍")) {
    Write-Host "  track not listed"
    exit 1
}
Start-Sleep -Seconds 16

Write-Host "== result"
Get-PlaybackState
Write-Host "== logs"
& $adb -s $serial logcat -d | Select-String -Pattern "PawAMPlayback|PawAMViewModel|Licence HTTP|Source error" | Select-Object -Last 4
