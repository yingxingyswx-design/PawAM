# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Playlist playback check with a foreground guard.
#
# Earlier attempts pressed Back until the app was gone, so every later tap landed on the launcher
# and produced false "no response" results. This script verifies at each step that PawAM is still
# the resumed activity before touching anything.

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "c412785e"
$dump = Join-Path $env:TEMP "pawam_guard.xml"

function Get-Ui {
    & $adb -s $serial shell "uiautomator dump /sdcard/pawam_guard.xml" 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/pawam_guard.xml $dump 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($dump, [System.Text.Encoding]::UTF8)
}

function Get-Nodes([string]$xml) {
    $nodes = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $tag = $m.Value
        $text = ([regex]::Match($tag, 'text="([^"]*)"')).Groups[1].Value
        $clickable = ([regex]::Match($tag, 'clickable="([^"]*)"')).Groups[1].Value -eq 'true'
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $nodes += [pscustomobject]@{
            Text = $text; Clickable = $clickable
            X1 = [int]$b.Groups[1].Value; Y1 = [int]$b.Groups[2].Value
            X2 = [int]$b.Groups[3].Value; Y2 = [int]$b.Groups[4].Value
        }
    }
    return $nodes
}

function Test-InApp {
    return ((& $adb -s $serial shell "dumpsys activity activities | grep topResumedActivity") -match 'pawam')
}

function Tap-Label([string]$label) {
    $nodes = Get-Nodes (Get-Ui)
    $target = $nodes | Where-Object { $_.Text -eq $label } | Select-Object -First 1
    if (-not $target) { Write-Host "  MISS '$label'"; return $false }

    $cx = [int](($target.X1 + $target.X2) / 2)
    $cy = [int](($target.Y1 + $target.Y2) / 2)

    $row = $nodes | Where-Object {
        $_.Clickable -and $_.X1 -le $cx -and $_.X2 -ge $cx -and $_.Y1 -le $cy -and $_.Y2 -ge $cy
    } | Sort-Object { ($_.X2 - $_.X1) * ($_.Y2 - $_.Y1) } | Select-Object -First 1

    if ($row) { $cx = [int](($row.X1 + $row.X2) / 2); $cy = [int](($row.Y1 + $row.Y2) / 2) }

    Write-Host "  TAP '$label' ($cx,$cy)"
    & $adb -s $serial shell "input tap $cx $cy" | Out-Null
    Start-Sleep -Seconds 3
    return $true
}

& $adb -s $serial shell "am force-stop dev.pawam.twelve.dev" | Out-Null
& $adb -s $serial logcat -c | Out-Null
& $adb -s $serial shell "am start -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
Start-Sleep -Seconds 9

Write-Host "inApp after launch: $(Test-InApp)"

# Leave the now-playing screen without leaving the app.
for ($i = 0; $i -lt 3; $i++) {
    if ((Get-Ui) -match '切换提供商') { break }
    & $adb -s $serial shell "input keyevent KEYCODE_BACK" | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Test-InApp)) {
        Write-Host "  back left the app; relaunching"
        & $adb -s $serial shell "am start -n dev.pawam.twelve.dev/org.lineageos.twelve.MainActivity" | Out-Null
        Start-Sleep -Seconds 6
    }
}

Write-Host "== library"
Tap-Label "库" | Out-Null
Start-Sleep -Seconds 4
Write-Host "  screen: $(((Get-Nodes (Get-Ui)) | Where-Object { $_.Text } | Select-Object -First 8 -ExpandProperty Text) -join ' / ')"

Write-Host "== playlists tab"
Tap-Label "播放列表" | Out-Null
Start-Sleep -Seconds 6
$labels = ((Get-Nodes (Get-Ui)) | Where-Object { $_.Text } | Select-Object -ExpandProperty Text -Unique) -join ' / '
Write-Host "  screen: $labels"

$playlistName = $null
foreach ($candidate in @('Dreamy', 'Lonely', 'MusicGames', '喜爱歌曲')) {
    if ($labels -match [regex]::Escape($candidate)) { $playlistName = $candidate; break }
}

if (-not $playlistName) {
    Write-Host "  no playlist visible"
    exit 1
}

Write-Host "== open playlist '$playlistName'"
Tap-Label $playlistName | Out-Null
Start-Sleep -Seconds 8

$xml = Get-Ui
$trackNode = (Get-Nodes $xml) | Where-Object {
    $_.Text -and $_.Text -notmatch '^\d+:\d+$' -and $_.Text -notmatch '音轨' -and $_.Y1 -gt 1100 -and $_.Y1 -lt 1400
} | Select-Object -First 1

if (-not $trackNode) {
    Write-Host "  no track row found"
    exit 1
}

Write-Host "== play '${($trackNode.Text)}'"
Tap-Label $trackNode.Text | Out-Null
Start-Sleep -Seconds 18

Write-Host "== result"
& $adb -s $serial logcat -d | Select-String -Pattern "onSessionPlaybackStateChanged.*pawam|PawAMPlaylist: row|PawAMViewModel|Licence HTTP|Source error" |
    Select-Object -Last 5
