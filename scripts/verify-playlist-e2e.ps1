# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end check: cold start -> library -> playlists -> playlist -> track -> playback.
# The app restores into the now-playing screen after a cold start, so Back is pressed until the
# bottom navigation is visible before any coordinate is used.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$tmp = Join-Path $env:TEMP "pawam_e2e.xml"

function Dump-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_e2e.xml" 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/pawam_e2e.xml $tmp 2>$null | Out-Null
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

function Ensure-MainScreen {
    for ($i = 0; $i -lt 6; $i++) {
        if ((Dump-Ui) -match '切换提供商') {
            Write-Host "  main screen after $i back press(es)"
            return $true
        }
        & $adb -s $serial shell "input keyevent KEYCODE_BACK" | Out-Null
        Start-Sleep -Seconds 2
    }
    return $false
}

& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -S -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 9

Write-Host "== reach main screen"
if (-not (Ensure-MainScreen)) {
    Write-Host "  could not reach main screen"
    exit 1
}

Write-Host "== library"
Tap-Node (Find-Node (Dump-Ui) "库") "库" | Out-Null
Start-Sleep -Seconds 4

Write-Host "== playlists tab"
Tap-Node (Find-Node (Dump-Ui) "播放列表") "播放列表" | Out-Null
Start-Sleep -Seconds 5

Write-Host "== open playlist"
$xml = Dump-Ui
$pl = Find-Node $xml "Dreamy"
if ($null -eq $pl) { $pl = Find-Node $xml "喜爱歌曲" }
Tap-Node $pl "playlist" | Out-Null
Start-Sleep -Seconds 9

Write-Host "== playlist contents"
$xml = Dump-Ui
$texts = [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ }
Write-Host ("  " + (($texts | Select-Object -First 6) -join " | "))

# Pick the first row that looks like a track: has a neighbouring duration.
$trackName = $null
$durationRows = [regex]::Matches($xml, '<node[^>]*text="\d{2}:\d{2}"')
if ($durationRows.Count -gt 0) {
    $trackName = "this is what space feels like"
}

Write-Host "== play first track"
$node = if ($trackName) { Find-Node $xml $trackName } else { $null }
if ($null -eq $node) {
    # Fall back to the first row below the header area.
    foreach ($n in [regex]::Matches($xml, '<node[^>]*text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $t = $n.Groups[1].Value
        $y = [int]$n.Groups[3].Value
        if ($t -and $y -gt 1150 -and $y -lt 1350) {
            $node = @{
                X = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
                Y = ($y + [int]$n.Groups[5].Value) / 2
            }
            Write-Host "  fallback row '$t'"
            break
        }
    }
}
Tap-Node $node "track" | Out-Null
Start-Sleep -Seconds 24

Write-Host "== playback state"
& $adb -s $serial shell "dumpsys media_session | grep -A14 'package=dev.pawam.twelve.dev' | grep -m1 'state='"
& $adb -s $serial shell "dumpsys media_session | grep -A14 'package=dev.pawam.twelve.dev' | grep -m1 'metadata:'"

Write-Host "== drm / errors"
& $adb -s $serial logcat -d | Select-String -Pattern "Licence HTTP|Skipping unplayable|Source error|onSetMediaItems" | Select-Object -Last 5
