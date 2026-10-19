# Gotchas

Everything here cost real time to work out. Read it before debugging anything.

## The DNS filter

**`am force-stop dnsfilter.android` breaks filtering silently.** The app comes
back (always-on VPN), the VPN network re-registers, and DNS stops going through
it: every lookup goes straight to the router. The traffic log goes quiet, which
is the tell. Recovery is a reboot, not a restart. Never force-stop it — the one
exception is `scripts/dnsfilter.sh`, which stops it during setup and reboots
immediately afterwards so the pushed list is the one that loads.

**Without storage permission the blocklist import writes a 234-byte header.**
This only matters for the `filterAutoUpdateURL` + `file://` route, which an
easier version of this repo used. The current script avoids it: the list is
composed on the computer and pushed as `FILTERHOSTS.TXT`, with auto-update
disabled. If you do go back to `file://` imports, grant `READ_EXTERNAL_STORAGE`
and `WRITE_EXTERNAL_STORAGE` first — a sideloaded app gets no permission prompt.

**`detectDNS = true` stops capturing DNS after a network change.** Pin
`detectDNS = false`, give an explicit `fallbackDNS` (the router) and set
`routeIPs = <gateway>`. The `routeIPs` line is what puts the router's resolver
inside the tunnel.

**The list is only rebuilt at boot**, and only when `FILTERHOSTS.TXT` is missing,
the configured interval has passed, or `filterAutoUpdateURL` is set. The script
sidesteps all of that by pushing the file itself and disabling auto-update; when
you edit the list, push it again and reboot.

**`additionalHosts.txt` is ignored** on at least Fire OS 7.7 builds — entries
there never take effect. Custom hosts belong in the pushed list
(`data/blocklists/extra-hosts.txt`, merged by `dnsfilter.sh`), not in that file.

**The VPN consent dialog is phone-sized on TVs** and often ignores directional
keys and taps. Pre-granting `appops ... ACTIVATE_VPN allow` skips it entirely.

## The launcher

**The accessibility service is the only hook, and it can be cleared.** If HOME
lands on Amazon's home screen, check:

```sh
adb shell settings get secure enabled_accessibility_services   # empty = broken
adb shell settings get secure accessibility_enabled             # 0 = broken
```

Re-run `scripts/launcher.sh`. Toggling the service off and on again is what
actually rebinds it. Force-stopping the launcher (`am force-stop tv.dumbtv.home`,
which any test harness and any APK update does) is enough to lose the binding:
the setting still reads enabled, and HOME silently goes back to Amazon's home.

**Enabled is not the same as bound.** `scripts/verify.sh` also reads
`dumpsys accessibility` and looks for `dumbtv home button` in the current
user's bound services. Android 9 labels this section `services`; newer builds
use `Bound services`. A service appearing only under `Enabled services` or
`Binding services` is not a running hook. Unknown dump formats fail verification
instead of reporting success. These dumps expose labels rather than component
IDs, so this check also requires the exact Guard component in the enabled list.
It does not press HOME or prove the physical remote works.

On the [TCL G08 build in the catalog](../catalog/devices/androidtv-tcl-g08.yaml),
an enabled but unbound companion service was blocked by `TclAppBoot` with
`callee doesn't have OP_AUTO_START permission`. Granting that app's `AUTO_START`
app-op and rebinding its service resolved it, including after reboot. This is
a TCL-specific observation, not a permission to grant on every TV. Preserve
other enabled accessibility services when rebinding. The verifier only reports
the failure; it never changes permissions or restarts services.

**Amazon's home for ~5 s on every HOME press** means
`alarm_manager_constants` lost `min_futurity=0` (an update or a settings reset).
Check with `adb shell settings get global alarm_manager_constants` and re-run
`scripts/launcher.sh`. With it set, HOME shows ~0.3 s of black, then the home
screen.

**The HOME key never reaches an accessibility key filter on Fire OS** — D-pad
keys do, HOME does not. Do not build on `onKeyEvent` for HOME; react to Amazon's
home window instead, as `launcher/` does.

**`android.settings.SETTINGS` reopens Amazon's home** on Fire OS: it resolves to a
stub (`CTSDummySettingsHandler`) that forwards to `HomeActivity_vNext`, which the
launcher then replaces. Open settings with `MAIN` +
`android.intent.category.LEANBACK_SETTINGS` instead.

**Injected keys skip accessibility filters.** `adb shell input keyevent HOME`
proves nothing about a real remote; to test like a remote, write to the remote's
input device: `sendevent /dev/input/eventN 1 172 1` (shell is in the `input`
group; find N with `getevent -pl`).

**After every boot the home screen takes ~15 s to appear**; until then Android shows
`com.amazon.tv.settings.v2/.system.FallbackHome`, a blank screen. That is startup
order, not damage.

## Packages

**A disabled package can still be running.** Persistent priv-apps ignore
`pm disable-user` for the *running process*: the state changes, the process
stays, and `am force-stop` is accepted but does not kill it. `debloat.sh` has a
second pass that denies such packages background work and data.

**`com.amazon.vizzini` must not be disabled.** It is persistent, and while
disabled it draws a non-focusable "Unable to Detect Your Remote" window over
everything that no tap can dismiss — taps go to the focused window underneath.
It is in `never-disable.txt`; block its endpoints instead.

**Protected lists change per build.** A package that disables on Fire OS 7.7.1.7
may be protected on another build, and vice versa. Trust the output, not the
file.

## Updates

**An Amazon update re-enables a lot of this.** The OTA packages are protected, so
the only defence is DNS: `softwareupdates.amazon.com` and the payload CDN must not
resolve. That is what `extra-hosts.txt` does. If the user ever unblocks them, or
factory-resets, re-run the pass.

## What never works

- `adb root` / `adb remount` — `user` build, `ro.secure=1`.
- Writing `/system/etc/hosts` — dm-verity, and the documented brick path.
- `pm hide`, `pm suspend`, `pm clear` on protected packages — missing permissions.
- Disabling the launcher — protected, and it leaves the TV with no home screen.

## Fire OS 6 (Android 7.1)

Found on a Fire TV Stick 4K (mantis, 2018) on Fire OS 6.7.1.1. The scripts read
the API level at connect time and switch to `data/fireos6/` on their own.

**No `cmd package install-existing`.** An Amazon app uninstalled with
`pm uninstall -k --user 0` would only come back with a factory reset, so
`debloat.sh` only disables system apps there. `restore.sh` then needs nothing but
`pm enable`.

**`com.amazon.firebat` is Prime Video**, not an OTA helper: launching it opens
`com.amazon.pyrocore.IgnitionActivity`. It is in `data/fireos6/never-disable.txt`.

**`com.amazon.vizzini` re-enables itself at every boot.** `pm disable-user`
answers `disabled-user`, and after a reboot it is enabled and running again. Leave
it alone either way.

**`ps -A -o NAME` answers `bad pid '-A'`.** Fire OS 6 still has the toolbox `ps`;
`running_names` in `scripts/lib.sh` falls back to its last column.

**No `RUN_ANY_IN_BACKGROUND`.** That app-op arrived in Android 9. `RUN_IN_BACKGROUND
ignore` exists and refuses background service starts (look for `rejectTime` in
`appops get`), but a service that started while the app was open keeps running
until the system needs the memory.

**`always_on_vpn_app` is gone after the reboot.** Fire OS 6 does not keep the
value `settings put` writes, so nothing starts the filter at boot except its own
receiver (`AUTOSTART = true` in its config). Android 7.1 sends no BOOT_COMPLETED
to an app that was force-stopped and not opened since, so `dnsfilter.sh` opens it
once more before the reboot. `verify.sh` checks `tun0` there instead of the setting.

**The app can write an empty list over the pushed one** (84 bytes: a
`# Downloaded by personalDNSFilter at:` header and nothing else). At start it
reloads at once when `previousAutoUpdateURL` differs from the update URL, or when
`FILTERHOSTS.TXT.DLD_CNT` is missing; with no `filterAutoUpdateURL` that reload
downloads nothing. The upstream template ships `previousAutoUpdateURL = <none>`,
which never equals the emptied URL, so this is not Fire OS 6 only. `dnsfilter.sh`
now empties `previousAutoUpdateURL` and writes `DLD_CNT` (whose mtime starts the
reload interval) after the push.

**The release launcher APK needs Android 9.** `launcher/install.sh` notices sdk 25
and builds from source with `MIN_SDK=25` (needs a JDK and `platforms;android-25`).

**Fire OS 6.7 refuses third-party accessibility services.** `settings put secure
enabled_accessibility_services ...` exits 0 and changes nothing; writing the row
through `content insert --uri content://settings/secure` does store it, but the
service is never bound (`dumpsys accessibility` shows `services:{}`), and the log
(`logcat -b all`) has `FireOSFwk:A11yAuthorizer ... "trust"#"false" ... "package"#"tv.dumbtv.home"`.
That is Amazon's allowlist, not a bug to work around.

**Fire OS 6 takes HOME over through the Keeper instead.** `Keeper` is a foreground
service that reads the foreground package from `UsageStats` (granted with
`appops set tv.dumbtv.home GET_USAGE_STATS allow`) and calls
`ActivityManager.moveTaskToFront` when Amazon's launcher appears. That is not an
activity launch, so the 5 s `APP_SWITCH_DELAY_TIME` does not apply: measured under
0.25 s with a real remote key on 6.7.1.1. `install.sh` starts it and checks
`dumpsys activity services`. At boot there is no dumbtv task yet, so the first
start goes through an alarm-fired `PendingIntent`; `BOOT_COMPLETED` arrives about
45–50 s after power-on, and dumbtv is front right after that. Older Fire OS 6
builds may still bind the accessibility service; the Keeper is harmless there.

**The Keeper must match the home activity, not just the package.** Fire TV
Settings (`MainSettingsActivity`) also lives in `com.amazon.tv.launcher`; matching
only the package pulls the user out of Settings. Check the event's class against
`com.amazon.tv.launcher.ui.HomeActivity_vNext`.

**The clock drifts while the filter VPN is up.** Fire OS 6 sends its time sync over
the active network, and the filter's VPN only carries DNS, so NTP and HTTP time
never answer. `SYSTEM_DNS=1` in `dumbtv.conf` adds `android` to the filter's
`androidAppWhiteList`; the system bypasses the tunnel and the clock syncs. The cost:
every package that runs as the system user (uid 1000) bypasses it too. On a Fire OS 7
TV that is 25 packages, including `com.amazon.device.logmanager`,
`com.amazon.media.recommendations` and `com.amazon.connectivitydiag`. Apps with their
own UID, and the OTA package, stay filtered. It is opt-in and honoured on Fire OS 6
only: Fire OS 7/8 keep their clock through the filter, so there it stays off.

**`monkey` exits with `SYS_KEYS has no physical keys`** when the remote has gone
to sleep, so it launches nothing. Pass `--pct-syskeys 0`, or launch apps with
`am start -n`.

## Fire OS 8 (Android 11)

From a Fire TV Stick 4K Max (AFTKM, Fire OS 8.1.8.2) in issue #8; the catalog entry is
`catalog/devices/fire-os-8-aftkm.yaml`.

**adb cannot see `/sdcard/Android/data/<app>`.** Listing, reading and pushing all get
`Permission denied`, so the filter's config and list cannot be pushed. `dnsfilter.sh`
detects this, pushes the list to `/sdcard/Android/media/dnsfilter.android/` instead
(adb can still write there) and prints the one step only the app's own screen can do:
Advanced settings > Configure filter update, add
`file://storage/emulated/0/Android/media/dnsfilter.android/dumbtv-blocklist.txt`, untick
the rest, reboot. The routing tweaks (`detectDNS = false`, `routeIPs`) cannot be set this
way. `verify.sh` skips the list count there and relies on the blocked-host lookups.

**Android 11 aborts the Guard's first launch.** The alarm-fired launch is a background
activity start (logcat: `Background activity start ... callingUidHasAnyVisibleWindow:
false` then `Abort background activity starts`). Android 11 lets it through when the
caller owns a visible window, and the Guard's cover overlay is one, but only once its
surface is on screen, a frame or two after `cover()`. The first launch loses that race,
so `Guard` fires it again at 60, 150, 300, 500, 800 and 1200 ms until `Home` resumes;
the second or third attempt lands. No `SYSTEM_ALERT_WINDOW` needed, and the launcher
keeps no permissions. Measured on a Fire TV Edition television (AFTCIBFBD1, Fire OS
8.1.8.0): 20/20 HOME presses from Netflix, YouTube, Spotify and Disney+ landed on dumbtv
home, under a second each, and it was in front after a reboot. Before the retry, 1 in 3.
`appops set tv.dumbtv.home SYSTEM_ALERT_WINDOW allow` does nothing here: the op stays
`default` for a package that does not declare the permission.

**`adb install` with recent platform-tools (37.0.1 here) ends with `Install command complete in N ms`**
(incremental install), so `install.sh` read that line instead of `Success`. It now
passes `--no-incremental`.

**`com.amazon.dummy.settings` and `com.android.traceur` come back enabled after a
reboot.** Re-run `debloat.sh` after a reboot to switch them off again.
