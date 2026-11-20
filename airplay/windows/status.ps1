# Shows the sweeper's state on Windows: adb authorization, task state, adb processes, last log lines.
$r = Join-Path $env:ProgramData 'dumbtv'
& "$r\platform-tools\adb.exe" devices | Select-Object -Skip 1
"task: " + (Get-ScheduledTask -TaskName 'dumbtv AirPlay sweeper').State
Get-CimInstance Win32_Process -Filter "Name = 'adb.exe'" | ForEach-Object { "adb: " + $_.CommandLine }
if (Test-Path "$r\airplay-sweeper.log") { Get-Content "$r\airplay-sweeper.log" -Tail 3 } else { "(no sweeper log yet)" }
