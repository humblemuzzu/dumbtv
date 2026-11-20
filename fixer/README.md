# dumbtv fixer

A tiny optional app: 20 seconds after each boot it switches ADB debugging off and on
(`Settings.Global.ADB_ENABLED` 1 → 0 → 1). On some Fire TVs network adb on port 5555 stays dead
after a reboot until a person does that in Developer Options, which cuts off every script and
every computer that manages the TV over the network.

- No UI, no INTERNET permission, one receiver for the protected `BOOT_COMPLETED` broadcast.
- Needs `WRITE_SECURE_SETTINGS`, which no app can get at install: `install.sh` grants it over
  adb with `pm grant`. It leaves ADB off if it was off.
- Logs under the tag `dumbtv-fixer`.

```sh
ADB_SERIAL=<tv-ip>:5555 ./fixer/install.sh          # build, install, grant
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
