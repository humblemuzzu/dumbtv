# AirPlay sweeper

An optional helper for a Fire TV whose AirPlay (screen mirroring from a Mac, iPhone or iPad)
works once and then fails: the Mac says it is sharing, the TV shows nothing, and after about
40 seconds the Mac gives up.

## The bug

AirPlay on Fire OS is two processes from `com.apple.airplay.amazon.airplay`:
`com.amazon.airplaydaemon` (the receiver: mDNS adverts and RTSP on port 7000, started only at
boot) and the player, the screen you see during a session.

1. Leaving a session, with HOME or BACK, ends it cleanly but leaves the player's task behind.
2. Low memory kills the background player soon after, seen 1.7 s after HOME on a 2 GB TV.
3. The Mac reconnects on its own about 10 s later. Android brings the old task back and
   rebuilds the player in it; the player quits about 4 s later without talking to the daemon.
   The daemon waits 40 s (`Websocket connection wait timed out`) while the Mac spins.
4. Once that task is gone, the next connection gets a fresh task and works.

An app on the TV cannot remove another app's task: `REMOVE_TASKS` and Amazon's
`amazon.aosp.permission.REMOVE_TASKS` are signature permissions, and since Fire OS 7.6.6.9 /
8.1.0.3 adbd refuses connections from the TV itself (CVE-2024-27350). `adb shell am stack remove`
from another machine on the network does it, so this runs on a computer that is usually on.

## What it does

It follows the TV's event log over network adb. The moment the player process dies
(`am_proc_died`), it removes the hidden AirPlay player tasks, but only while no player process
is running, so a live session is never touched. It also sweeps once on every (re)connect, for
example after the computer wakes. Several computers can run it at once. It logs only removals.

It changes nothing on the TV except removing those dead tasks. Never force-stop
`com.apple.airplay.amazon.airplay`: only a reboot starts the daemon again.

## Install

Allow the computer's adb key on the TV once (`adb connect <tv-ip>:5555`, then **Allow** on the
TV, with "Always allow"). If no prompt appears, switch ADB debugging off and on in Developer
Options and connect again.

macOS (a launchd agent for the logged-in user, kept alive by launchd):

```sh
ADB_SERIAL=<tv-ip>:5555 ./airplay/install.sh     # install or update
./airplay/install.sh --undo                      # remove
tail -f ~/Library/Logs/dumbtv-airplay-sweeper.log
```

Windows (a scheduled task that runs as SYSTEM from boot; run PowerShell as administrator):

```powershell
powershell -ExecutionPolicy Bypass -File airplay\windows\install.ps1 -Tv <tv-ip>:5555
powershell -ExecutionPolicy Bypass -File airplay\windows\install.ps1 -Undo
powershell -ExecutionPolicy Bypass -File airplay\windows\status.ps1
```

The Windows installer downloads Google's platform-tools into `%ProgramData%\dumbtv`, and adb's
key lives in `%ProgramData%\dumbtv\android`, so it is the same whichever account runs it.

## Keeping network adb on: dumbtv fixer

Fire OS 8.1.8.5 switches ADB debugging off during every boot, so the sweeper loses the TV
after a reboot until someone toggles it in Developer Options. [dumbtv fixer](../fixer/README.md)
is a small app on the TV that switches it back on. The sweeper works without it; the fixer
only saves that manual step.

## Related

- dumbtv quick settings (`quick/`, where it is part of this repository and installed) shows the AirPlay state (Ready, In
  use, or "Macs can't see the TV, restart it" when the daemon died) and can free memory for it.
  Neither needs the other.
- An occasional failed first connection after a long idle is on the Mac's side; try again.

Tested on a Fire OS 8.1.8.5 television (AFTHY7ABBA) with a macOS and a Windows 10 sweeper on
real sessions: the dead task was removed 0.47 s after the player died, and the Mac's automatic
reconnect then worked.
