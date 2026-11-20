# Event-driven clean-up for the Fire TV AirPlay bug: Windows port of ../sweep.sh (same rules).
# Watches the TV's event log; when the AirPlay player process dies, removes the AirPlay tasks it
# left, but only while no player process runs, so a live session is never touched. Also sweeps
# once per (re)connect. Runs forever under the "dumbtv AirPlay sweeper" scheduled task.
param(
    [Parameter(Mandatory = $true)][string]$Tv,
    [string]$Root = (Join-Path $env:ProgramData 'dumbtv')
)
$ErrorActionPreference = 'Continue'
$Adb = Join-Path $Root 'platform-tools\adb.exe'
$Log = Join-Path $Root 'airplay-sweeper.log'
$Player = 'com.apple.airplay.amazon.airplay'
# adb keeps its key under ANDROID_USER_HOME: one fixed place, whichever account runs this.
$env:ANDROID_USER_HOME = Join-Path $Root 'android'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

function Note([string]$msg) {
    Add-Content -Path $Log -Value ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg)
}

# Runs adb with a time limit; returns @{Code; Out}.
function Adb([string[]]$adbArgs, [int]$timeoutMs = 15000) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = ($adbArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEndAsync()
    if (-not $p.WaitForExit($timeoutMs)) { try { $p.Kill() } catch {} ; return @{ Code = -1; Out = '' } }
    return @{ Code = $p.ExitCode; Out = $out.Result }
}

function Connected {
    if ((Adb @('-s', $Tv, 'get-state')).Out.Trim() -eq 'device') { return $true }
    Adb @('connect', $Tv) | Out-Null
    return ((Adb @('-s', $Tv, 'get-state')).Out.Trim() -eq 'device')
}

function Sweep([string]$why) {
    if ((Adb @('-s', $Tv, 'shell', 'pidof', $Player)).Code -eq 0) { return }
    $list = (Adb @('-s', $Tv, 'shell', 'am', 'stack', 'list')).Out
    $re = '(?m)^\s*taskId=(\d+): ' + [regex]::Escape($Player) + '/\S* .* visible=false(\s.*)?$'
    foreach ($m in [regex]::Matches($list, $re)) {
        $id = $m.Groups[1].Value
        if ((Adb @('-s', $Tv, 'shell', 'am', 'stack', 'remove', $id)).Code -eq 0) {
            Note "removed dead AirPlay task $id ($why)"
        }
    }
}

$needle = ",$Player,"
while ($true) {
    if (Connected) {
        Sweep 'connect'
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Adb
        # -T 1: start at the newest line, so old deaths are not replayed.
        $psi.Arguments = "-s $Tv logcat -v brief -b events -T 1"
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.CreateNoWindow = $true
        $stream = [System.Diagnostics.Process]::Start($psi)
        while ($null -ne ($line = $stream.StandardOutput.ReadLine())) {
            if ($line.Contains('am_proc_died') -and $line.Contains($needle)) { Sweep 'player died' }
        }
        try { $stream.Kill() } catch {}
    }
    Start-Sleep -Seconds 10
}
