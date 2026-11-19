# dumbtv fixer

A tiny optional app that keeps network adb (port 5555) usable across reboots. On Fire OS 8.1.8.5
the boot sets `Settings.Global.ADB_ENABLED` to 0, so the port stays closed until a person toggles
ADB debugging in Developer Options, which cuts off every script and computer that manages the TV.

At `BOOT_COMPLETED`, and again 45 s and 120 s later (Fire OS may switch ADB off after the boot
broadcast):
- ADB off and the owner opted in (`install.sh` creates
  `/sdcard/Android/media/tv.dumbtv.fixer/keep-adb-on`): turn it on.
- ADB on, at the boot check: switch it off and on, which reopens the port.
- It acts only in those first minutes, so turning ADB off later in Developer Options sticks until
  the next boot. Delete `keep-adb-on` (or uninstall) to stop it entirely.

Details:
- No UI, no INTERNET permission. Needs `WRITE_SECURE_SETTINGS`, which no app can get at install:
  `install.sh` grants it over adb with `pm grant`.
- The work runs inside `onReceive`, not on a `goAsync()` thread: on this TV Android killed the
  process ("empty") between "off" and "on" in the first version, which left ADB off.
- Every run is appended to `/sdcard/Android/media/tv.dumbtv.fixer/fixer.log` (logcat's main
  buffer rotates away within minutes of boot here).
- Test hook only adb's shell can reach (`android.permission.DUMP`):
  `adb shell am broadcast -n tv.dumbtv.fixer/.NowReceiver` (adb drops for ~2 s).

```sh
ADB_SERIAL=<tv-ip>:5555 ./fixer/install.sh          # build, install, grant, opt in
ADB_SERIAL=<tv-ip>:5555 ./fixer/install.sh --undo   # remove
```

## Why it does not do more

It started out as an adb client that dumbtv home would call to remove the AirPlay player's
leftover task (a removed task makes the next AirPlay session start fresh). That cannot work on
current Fire OS: since 7.6.6.9 / 8.1.0.3 adbd drops connections that come from the TV itself,
over 127.0.0.1 and over the TV's own LAN address alike (CVE-2024-27350). No permission an app can
be granted removes another app's task (`REMOVE_TASKS` is signature-only, as is Amazon's
`amazon.aosp.permission.REMOVE_TASKS`). That clean-up has to come from another machine on the
network, over adb.
