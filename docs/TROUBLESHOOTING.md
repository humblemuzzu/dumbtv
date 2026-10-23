# Troubleshooting

Start with `./scripts/verify.sh`. It checks the filter, the home screen and the
settings, and every `FAIL` line names what is wrong. Find that symptom below.

To put the TV back the way it was: `./scripts/restore.sh`.

**Contents:** [Home screen](#home-screen) · [DNS filter](#dns-filter) ·
[Packages and apps](#packages-and-apps) · [Updates](#updates) ·
[Fire OS 6](#fire-os-6) · [Fire OS 8](#fire-os-8) · [Testing like a remote](#testing-like-a-remote)

## Home screen

### Amazon's home screen came back

The launcher's HOME hook is an accessibility service, and it can be switched off
by an update, a settings reset, or a force-stop of the launcher (which every APK
update does). Check:

```sh
adb shell settings get secure enabled_accessibility_services   # empty = off
adb shell settings get secure accessibility_enabled             # 0 = off
```

Run `./scripts/launcher.sh -y`. It switches the service off and on again, which is
what makes Android bind it.

### `verify.sh` says the home hook is enabled but not bound

Enabled is a setting; bound means the service is actually running. `verify.sh`
reads `dumpsys accessibility` and only passes when "dumbtv home button" is in the
current user's bound services. Run `./scripts/launcher.sh -y`.

On a TCL G08 the service stayed unbound because TCL's `TclAppBoot` refused it
(`callee doesn't have OP_AUTO_START permission`). Granting the app's `AUTO_START`
app-op fixed it. That is a TCL rule, not something to grant on every TV; see
[docs/devices/tcl-g08.md](devices/tcl-g08.md).

### Amazon's home shows for about five seconds before dumbtv home

The `min_futurity=0` alarm setting was lost (an update or a settings reset). Check
with `adb shell settings get global alarm_manager_constants`, then run
`./scripts/launcher.sh -y`. With it set, HOME shows about 0.3 s of black.

### Settings bounces back to the home screen

On Fire OS, `android.settings.SETTINGS` opens a stub that forwards to Amazon's
home, which the launcher then replaces. The launcher's Settings button already
avoids this. From adb, open settings with:

```sh
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_SETTINGS
```

### A blank screen for about 15 seconds after boot

That is Android's `FallbackHome` while the system starts. Nothing is broken.

### HOME from an HDMI input takes about five seconds

On Fire TV Edition televisions Amazon's Live TV app holds the display while it
closes. Nothing outside that app can shorten it. Open inputs with the launcher's
own screen instead (Options › Open inputs with › Automatic or dumbtv home): it
releases the picture about 0.4 s after HOME.

### An HDMI tile opens Live TV settings instead of the input

Amazon's Live TV app claims every input and then shows its settings or a channel
scan. Set Options › Open inputs with › **Automatic** (the default from launcher
1.2), which uses the launcher's own input screen when that happens.

## DNS filter

### Nothing is blocked, but a `tun` interface is up

The filter app was force-stopped. After `am force-stop dnsfilter.android` the VPN
comes back but DNS no longer goes through it. A reboot fixes it; restarting the app
does not. Never force-stop it. `dnsfilter.sh` stops it once during setup and
reboots straight after.

### Nothing is blocked after a reboot

Check `adb shell settings get secure always_on_vpn_app`. Fire OS 6 always forgets
this setting at boot, and at least one Fire OS 7 build sometimes does; the app's
own boot start brings the filter up instead. `verify.sh` accepts a running `tunN`
interface in that case. The number changes between boots (`tun0`, then `tun1`).

If the setting is there and a tunnel is up, yet DNS still goes around it, the
always-on VPN and the app's own boot start are racing (seen on an AFTTI43, issue
#58). Add `FILTER_AUTOSTART=0` to `dumbtv.conf` and run `./scripts/dnsfilter.sh`
again. Fire OS 6 needs the app's own start, so the script ignores this setting
there.

### The filter stopped working after the TV changed network

The config pins `detectDNS = false`, an explicit `fallbackDNS` and
`routeIPs = <gateway>`. If the TV moved to a network with a different router, run
`./scripts/dnsfilter.sh` again so those point at the new one.

### The block list is nearly empty

The app wrote its own empty list over the pushed one: a 234-byte or 84-byte file
with only a header. `dnsfilter.sh` prevents both cases (it empties
`previousAutoUpdateURL`, writes `FILTERHOSTS.TXT.DLD_CNT` and grants the storage
permission the Fire OS 8 import needs). Run it again.

### Edits to the block list did nothing

The app rebuilds its index only at boot. Change `data/blocklists/extra-hosts.txt`
and run `./scripts/dnsfilter.sh`; it pushes the list, marks the index outdated and
reboots. The app's own `additionalHosts.txt` is ignored on Fire OS 7.

### A streaming app stopped working

A host it needs is blocked. Remove that exact host from
`data/blocklists/extra-hosts.txt` and run `./scripts/dnsfilter.sh`. Prime Video
needs its video CDN and Amazon's push service: answer yes to Prime in
`configure.sh` (`PRIME=1`, the default) and both stay reachable.

### The clock is wrong on Fire OS 6

Fire OS 6 syncs its clock over the active network, which is the DNS-only VPN.
`SYSTEM_DNS=1` in `dumbtv.conf` lets system apps bypass the filter so the clock
syncs. Every app running as the system user then bypasses it too, so it is off by
default and ignored on Fire OS 7 and 8, which keep their clock anyway.

### The VPN consent box is stuck on screen

It is phone-sized on TVs and ignores the remote. `dnsfilter.sh` pre-approves it
with `appops set dnsfilter.android ACTIVATE_VPN allow`, so it should never show.
If it does, run the script again.

## Packages and apps

### "Unable to Detect Your Remote" covers everything

`com.amazon.vizzini` was disabled. It is a persistent system app that draws that
window while disabled, and taps cannot dismiss it. It is on `never-disable.txt`.

```sh
adb shell pm enable --user 0 com.amazon.vizzini
adb reboot
```

### Prime Video does not open

`com.amazon.firebat` is Prime Video on Fire OS 6, 7 and 8, despite the name. It is
on `never-disable.txt` now; a TV debloated by an older version can get it back
with `./scripts/restore.sh` or `adb shell pm enable --user 0 com.amazon.firebat`.
Also check that `PRIME=1` in `dumbtv.conf`.

### A package I disabled is still running

Persistent system apps keep their process after `pm disable-user`, and
`am force-stop` does not end it. `debloat.sh` has a second pass for these: it
takes away their background work and background data.

### `debloat.sh` reports `PROTECTED`

Amazon refuses to let the shell disable that package. Which packages are protected
changes between builds. Add it to `data/fireos/protected.txt`; `lockdown.sh` takes
away its background work and data, and the DNS filter blocks its servers.

### Some packages come back after a reboot

`com.amazon.dummy.settings`, `com.android.traceur` and `com.amazon.vizzini`
re-enable themselves on some builds. Run `./scripts/debloat.sh` again; it is safe
to repeat.

## Updates

### An Amazon update undid the cleanup

Updates re-enable packages and reset settings. The updater itself is protected, so
the DNS filter is what keeps updates away: it blocks `softwareupdates.amazon.com`
and the download servers. If you turned the filter off or factory-reset the TV,
run `./scripts/all.sh` again.

To install an update on purpose: `./scripts/restore.sh`, let the TV update, then
run `./scripts/all.sh`.

## Fire OS 6

Found on a Fire TV Stick 4K (AFTMM, Fire OS 6.7.1.1). The scripts read the API
level and switch to `data/fireos6/` on their own.

- **HOME never switches to dumbtv home.** Fire OS 6.7 refuses third-party
  accessibility services (`A11yAuthorizer ... "trust"#"false"` in
  `logcat -b all`). The launcher uses its Keeper service there instead; run
  `./scripts/launcher.sh -y` to re-arm it.
- **dumbtv home will not install.** Releases up to 1.1 needed Android 9. Install
  1.2 or later with `./launcher/install.sh`.
- **`monkey` launches nothing** and says `SYS_KEYS has no physical keys` when the
  remote is asleep. Use `am start -n`, or pass `--pct-syskeys 0`.

## Fire OS 8

- **Nothing is blocked after `dnsfilter.sh`.** adb cannot write the app's folder
  on Android 11, so the script pushes the list to
  `/sdcard/Android/media/dnsfilter.android/` and you point the app at it once:
  Advanced settings › Configure filter update › add
  `file:///storage/emulated/0/Android/media/dnsfilter.android/dumbtv-blocklist.txt`
  (three slashes), untick the other entries, reboot. `verify.sh` then checks the
  blocked hosts directly.
- **The on-screen keyboard swallows keys** while a text field is focused. Batch
  deletes into one call (`input keyevent 67 67 67`), and close the keyboard with
  BACK before tapping another field.
- **The launcher's own folder is closed to adb too,** so `home.conf` and the
  wallpapers cannot be pushed to `/sdcard/Android/data/tv.dumbtv.home/files/`.
  Write them to `/sdcard/Android/media/tv.dumbtv.home/` instead: the launcher reads
  both, wallpapers in name order, and a settings file pushed to the media folder is
  applied once, then remembered, so it does not overwrite a later change made in
  Options. [launcher/README.md](../launcher/README.md) has the commands.
- **HOME stays on Amazon's home with launcher 1.1.** Android 11 needs the
  launcher to have a visible window first; 1.2 waits for its cover window. Update
  with `./launcher/install.sh`.

## Testing like a remote

`adb shell input keyevent HOME` skips accessibility filters, so it proves nothing
about the real remote. Write to the remote's input device instead (find `N` with
`getevent -pl`):

```sh
adb shell sendevent /dev/input/eventN 1 172 1
adb shell sendevent /dev/input/eventN 0 0 0
adb shell sendevent /dev/input/eventN 1 172 0
adb shell sendevent /dev/input/eventN 0 0 0
```
