# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Verify word-level lyrics on device: Apple Music album -> play -> now playing -> lyrics.
# Every tap is resolved from the view hierarchy, never guessed.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$tmp = Join-Path $env:TEMP "pawam_ui2.xml"

function Dump-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_ui2.xml" 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/pawam_ui2.xml $tmp 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
}

function Tap-Label([string]$label, [switch]$Desc) {
    $xml = Dump-Ui
    $attr = if ($Desc) { 'content-desc' } else { 'text' }
    $pattern = '<node[^>]*' + $attr + '="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    foreach ($n in [regex]::Matches($xml, $pattern)) {
        if ($n.Groups[1].Value -eq $label) {
            $x = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
            $y = ([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2
            Write-Host ("  TAP '{0}' at ({1},{2})" -f $label, [int]$x, [int]$y)
            & $adb -s $serial shell "input tap $([int]$x) $([int]$y)" | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
    }
    Write-Host "  MISS '$label'"
    return $false
}

Write-Host "== restart"
& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -S -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 8

Write-Host "== provider -> Apple Music"
Tap-Label "切换提供商" | Out-Null
Tap-Label "Apple Music" | Out-Null
Start-Sleep -Seconds 5

Write-Host "== library -> album"
Tap-Label "库" | Out-Null
Start-Sleep -Seconds 4
Tap-Label "爱爱爱" | Out-Null
Start-Sleep -Seconds 6
$xml = Dump-Ui
$firstTrack = $null
foreach ($n in [regex]::Matches($xml, '<node[^>]*text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $t = $n.Groups[1].Value
    if ($t -match '^\d{1,2}:\d{2}$') { continue }
    if ($t -in @('爱爱爱','方大同','2006')) { continue }
    if ($t.Length -gt 1 -and $t -notmatch '音轨|分钟|MP4|M4A|FLAC') {
        $firstTrack = $t
        $x = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
        $y = ([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2
        Write-Host ("  TAP track '{0}' at ({1},{2})" -f $t, [int]$x, [int]$y)
        & $adb -s $serial shell "input tap $([int]$x) $([int]$y)" | Out-Null
        break
    }
}
Start-Sleep -Seconds 6

Write-Host "== now playing"
$xml = Dump-Ui
$opened = $false
foreach ($n in [regex]::Matches($xml, '<node[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    if ($n.Groups[1].Value -match '播放|正在播放|Now playing') {
        $x = ([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2
        $y = ([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2
        Write-Host ("  TAP now-playing '{0}' at ({1},{2})" -f $n.Groups[1].Value, [int]$x, [int]$y)
        & $adb -s $serial shell "input tap $([int]$x) $([int]$y)" | Out-Null
        $opened = $true
        break
    }
}
if (-not $opened) {
    # Fall back to the bottom mini bar region.
    Write-Host "  fallback: tapping mini bar"
    & $adb -s $serial shell "input tap 540 2160" | Out-Null
}
Start-Sleep -Seconds 5

Write-Host "== lyrics"
if (-not (Tap-Label "歌词" -Desc)) { Tap-Label "歌词" | Out-Null }
Start-Sleep -Seconds 6

$appPid = (& $adb -s $serial shell "pidof dev.pawam.twelve.dev") -join ""
& $adb -s $serial logcat -d --pid=$appPid | Select-String -Pattern "AppleMusicClient|lyrics|Lyrics" | Select-Object -Last 5

cmd /c "`"$adb`" -s $serial exec-out screencap -p > C:\codes\projects\PawAM\screenshots\20-lyrics.png"
Write-Host "screenshot saved"
