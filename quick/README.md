# dumbtv quick settings

An optional panel that slides in from the right when you press dumbtv home's **Settings** chip
(the cog), or **☰ Menu** while an HDMI input is on screen. Without it installed, the cog opens
the TV's settings as before. Holding HOME still opens Fire OS's own quick menu.

<p align="center">
  <img src="../docs/assets/screens/quick.png" alt="The quick settings panel over the home screen" width="720">
</p>
<p align="center">
  <img src="../docs/assets/screens/quick-warmth.png" alt="Warmth at 40 percent, tinting the whole screen" width="355">
  <img src="../docs/assets/screens/quick-sleep.png" alt="Sleep timer set to 30 minutes" width="355">
</p>

| Row | Left / right | OK |
|---|---|---|
| Brightness | backlight in 5 % steps (day or night level) | switches **dim after sunset** on/off |
| Bluetooth | cycles paired audio devices, then Settings… | connects or disconnects device; on Settings… opens Bluetooth screen |
| AirPlay | | glance: Ready / In use / Restart TV; OK makes room (`killBackgroundProcesses`) |
| Picture off | | turns screen black + min backlight (sound continues); any key restores |
| Network | | glance: Filter on/off + Wi-Fi signal/band; OK restarts DNS filter |
| TV health | | glance: All as set or warning; OK cycles through detail items |
| Auto-switch | after 4 min / paused until morning / off | (home only) pauses Chromecast idle auto-switch |
| Warmth | cold to warm in 10 % steps | switches between always on and **after sunset only** |
| Sleep timer | 15-minute steps, up to 4 hours | |
| Noise level | | live ambient dB bar while panel is open |

At the bottom: the time and date, memory in use, and the video format. Over an HDMI input that is
the signal (`1080p60`, `SDR · HDMI`); over the home screen it is the panel's own mode (`4K60`).
BACK or Menu slides the panel away.

## Install

```sh
ADB_SERIAL=<tv-ip>:5555 ./quick/install.sh          # build, install, grant
ADB_SERIAL=<tv-ip>:5555 ./quick/install.sh --undo   # give up the device admin, uninstall
```

It is signed with dumbtv home's key (`launcher/release.keystore`), so build dumbtv home first.
`scripts/restore.sh` removes it too.

## Why a separate app

dumbtv home asks for one permission on Fire OS 7 and 8, and CI holds it to that. The panel needs
several, so they go to this app alone. None of them can be granted on the TV; `install.sh` grants
each over adb:

| For | Grant | Why |
|---|---|---|
| Brightness | `appops set tv.dumbtv.quick WRITE_SETTINGS allow` | writes `system screen_brightness`, which the TV's display driver applies to the backlight |
| Warmth & Picture off | `appops set tv.dumbtv.quick SYSTEM_ALERT_WINDOW allow` | tinted window and black overlay over every app and input |
| Video format & AirPlay | `pm grant tv.dumbtv.quick android.permission.READ_LOGS` | reads `DispService` tag and events log buffer for AirPlay process lifecycle |
| Sleep timer | `dpm set-active-admin tv.dumbtv.quick/.Admin` | `DevicePolicyManager.lockNow()`, the remote's standby; force-lock is its only policy |
| Noise meter | `pm grant tv.dumbtv.quick android.permission.RECORD_AUDIO` | samples mic live only while panel is open; nothing recorded or saved |

Normal permissions granted at install include `BLUETOOTH`, `BLUETOOTH_ADMIN` (connect/disconnect audio), `KILL_BACKGROUND_PROCESSES` (AirPlay make-room), `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` (Wi-Fi glance), `RECEIVE_BOOT_COMPLETED` (restart filter broadcast). It has no `INTERNET` permission.

## How it works, and the limits found on a Fire TV television (AFTHY7ABBA, Fire OS 8.1)

- **Brightness is per source with dim after sunset.** The TV keeps a backlight level for each input
  in its picture settings and writes it to `screen_brightness` when the input changes. The panel
  remembers its own level for HDMI and for apps and home, and writes it back just after the TV does.
  When auto-dim is on, day and night levels are kept per source and blended with `Sun.night()`.
- **Warmth and Picture off are overlays.** A window is the only display control accessible to a
  normal app. Picture off draws an opaque black overlay with minimum backlight while sound keeps
  playing; any remote key except volume restores the picture.
- **After sunset** is worked out on the TV offline, with no network or location permission: the place
  comes from the time zone (a table of common zones, otherwise the UTC offset at 40° N), and the
  times from the US Naval Observatory sunrise equation. Warmth fades in over 30 minutes after
  sunset and out over the 30 minutes before sunrise.
- **The video format and AirPlay** come from the log. DispService logs HDMI input modes. The events
  buffer tracks the AirPlay player process (`com.apple.airplay.amazon.airplay`) and daemon.
- **The sleep timer** is an exact alarm. An active device admin cannot be uninstalled, and
  `dpm remove-active-admin` only removes test admins, so `install.sh --undo` first asks the app
  to give it up (`Undo`, a receiver only adb's shell can reach).

## Files

| | |
|---|---|
| `Panel.java` | the panel and its rows (scrollable on 540 dp screens) |
| `Tint.java` | foreground service: warmth overlay, picture off, DispService & event log, sunset tick |
| `Backlight.java` | brightness per source with day/night auto dim |
| `Network.java` | filter on/off and Wi-Fi band/RSSI check |
| `Health.java` | TV health expected-state checker (tests: `tests/HealthTest.java`) |
| `Noise.java` | live AudioRecord RMS dB meter |
| `Video.java` | DispService log parser (tests: `tests/VideoTest.java`) |
| `Sun.java` | sunrise, sunset, and dim blend (tests: `tests/SunTest.java`) |
| `Sleep.java`, `Admin.java` | sleep timer |
| `Boot.java`, `Undo.java`, `Compat.java` | start at boot, uninstall hook, Android 8 calls by reflection |
