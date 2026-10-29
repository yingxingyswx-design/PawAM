# SPDX-FileCopyrightText: 2026 The PawAM Project
# SPDX-License-Identifier: Apache-2.0
#
# Reliable UI driver for PawAM verification.
#
# Coordinate guessing has repeatedly produced false negatives: a tap lands on a text label rather
# than its clickable row, the screen does not change, and the resulting log looks like a product
# bug. This helper resolves a label to the *clickable ancestor* that geometrically contains it and
# taps the centre of that node instead.

$ErrorActionPreference = 'Continue'
$script:Adb = "C:\Users\paw_C\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:Serial = "c412785e"
$script:DumpPath = Join-Path $env:TEMP "pawam_driver.xml"

function Get-UiXml {
    & $script:Adb -s $script:Serial shell "uiautomator dump /sdcard/pawam_driver.xml" 2>$null | Out-Null
    & $script:Adb -s $script:Serial pull /sdcard/pawam_driver.xml $script:DumpPath 2>$null | Out-Null
    return [System.IO.File]::ReadAllText($script:DumpPath, [System.Text.Encoding]::UTF8)
}

function Get-Nodes([string]$xml) {
    $nodes = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $tag = $m.Value
        $text = ([regex]::Match($tag, 'text="([^"]*)"')).Groups[1].Value
        $desc = ([regex]::Match($tag, 'content-desc="([^"]*)"')).Groups[1].Value
        $clickable = ([regex]::Match($tag, 'clickable="([^"]*)"')).Groups[1].Value -eq 'true'
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $nodes += [pscustomobject]@{
            Text = $text
            Desc = $desc
            Clickable = $clickable
            X1 = [int]$b.Groups[1].Value
            Y1 = [int]$b.Groups[2].Value
            X2 = [int]$b.Groups[3].Value
            Y2 = [int]$b.Groups[4].Value
        }
    }
    return $nodes
}

function Tap-Label([string]$label) {
    $nodes = Get-Nodes (Get-UiXml)

    $target = $nodes | Where-Object { $_.Text -eq $label -or $_.Desc -eq $label } | Select-Object -First 1
    if (-not $target) {
        Write-Host "  MISS '$label'"
        return $false
    }

    $cx = [int](($target.X1 + $target.X2) / 2)
    $cy = [int](($target.Y1 + $target.Y2) / 2)

    # Prefer the smallest clickable node whose bounds contain the label's centre: that is the row.
    $clickable = $nodes | Where-Object {
        $_.Clickable -and
        $_.X1 -le $cx -and $_.X2 -ge $cx -and
        $_.Y1 -le $cy -and $_.Y2 -ge $cy
    } | Sort-Object { ($_.X2 - $_.X1) * ($_.Y2 - $_.Y1) } | Select-Object -First 1

    if ($clickable) {
        $cx = [int](($clickable.X1 + $clickable.X2) / 2)
        $cy = [int](($clickable.Y1 + $clickable.Y2) / 2)
        Write-Host "  TAP '$label' via clickable node ($cx,$cy)"
    } else {
        Write-Host "  TAP '$label' via label node ($cx,$cy)"
    }

    & $script:Adb -s $script:Serial shell "input tap $cx $cy" | Out-Null
    Start-Sleep -Seconds 3
    return $true
}

function Set-MainScreen {
    for ($i = 0; $i -lt 6; $i++) {
        $xml = Get-UiXml
        if ($xml -match '切换提供商') { return $true }
        & $script:Adb -s $script:Serial shell "input keyevent KEYCODE_BACK" | Out-Null
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-PlaybackState {
    $out = & $script:Adb -s $script:Serial shell "dumpsys media_session" 2>&1
    $idx = ($out | Select-String -Pattern "package=dev.pawam.twelve.dev" | Select-Object -First 1).LineNumber
    if (-not $idx) { return "no session" }
    $window = $out[($idx - 1)..([Math]::Min($idx + 14, $out.Count - 1))]
    $state = ($window | Select-String -Pattern "state=PlaybackState" | Select-Object -First 1).Line
    $meta = ($window | Select-String -Pattern "metadata:" | Select-Object -First 1).Line
    return (($state + "`n" + $meta).Trim())
}
