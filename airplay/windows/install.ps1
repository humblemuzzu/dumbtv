# Installs the AirPlay sweeper on Windows (run as administrator):
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Tv <tv-ip>:5555   install or update
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Undo              remove (keeps platform-tools)
# Puts Google's platform-tools and sweep.ps1 in %ProgramData%\dumbtv and registers a
# scheduled task that runs sweep.ps1 as SYSTEM from boot, whether or not anyone is logged in.
# The first run makes the TV ask "Allow USB debugging?" for this machine's key: accept it once.
param([switch]$Undo, [string]$Tv)
$ErrorActionPreference = 'Stop'
$Root = Join-Path $env:ProgramData 'dumbtv'
$Task = 'dumbtv AirPlay sweeper'

if (Get-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false
}
Get-Process adb -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Root*" } | Stop-Process -Force
if ($Undo) { Write-Output "removed task '$Task'"; return }
if (-not $Tv) { throw 'give the TV: -Tv <tv-ip>:5555' }

New-Item -ItemType Directory -Force -Path $Root | Out-Null
$adb = Join-Path $Root 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $Root -Force
    Remove-Item $zip
}
Copy-Item -Force (Join-Path $PSScriptRoot 'sweep.ps1') (Join-Path $Root 'sweep.ps1')

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Root\sweep.ps1`" -Tv $Tv"
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -StartWhenAvailable -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName $Task -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Start-ScheduledTask -TaskName $Task
Start-Sleep -Seconds 3
Get-ScheduledTask -TaskName $Task | Select-Object TaskName, State | Format-Table -AutoSize | Out-String | Write-Output
& $adb version | Select-Object -First 1
Write-Output "installed. Log: $Root\airplay-sweeper.log"
