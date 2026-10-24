# How it works

Everything runs over ADB as the `shell` user (uid 2000), the same access Fire OS
gives you when you switch on ADB debugging. Nothing needs root, and nothing is
written to `/system`.

There are five layers. Each one exists because the layer before it cannot reach
everything.

## 1. Packages: disabled, never uninstalled

`debloat.sh` runs `pm disable-user --user 0 <package>` for every entry in
`data/fireos/packages.txt` (`data/fireos6/` on Fire OS 6). The code stays in
`/system`, the package stops running, and `pm enable` or the TV's own
Settings › Applications brings it back.

The list holds only Amazon's ads, viewing trackers (ACR), telemetry, experiments,
update nags and empty stubs. Three other lists decide what is left alone:

| List | What is in it | What happens to it |
|---|---|---|
| `never-disable.txt` | home screen, settings, inputs, tuner, remote pairing, Prime Video, Alexa's runtime | never touched, even if named |
| `alexa.txt` | Alexa and the app that owns the microphone | kept; disabled only with `ALEXA=0` |
| `user-apps.txt` | streaming apps, Amazon Music, Photos, Silk, the screensaver, printing | kept; removed only when named |

An app the owner names is disabled if it shipped with the TV, and uninstalled if
it came from a store.

When Amazon refuses (`Cannot disable a protected package`), the package moves to
layer 3. Which packages are protected differs between builds.

## 2. Settings: `settings put`

Ad tracking, interest-based ads, usage reporting, promo autoplay and animation
speed are `Settings.Secure` and `Settings.Global` keys, the same ones the
on-screen toggles write. They are in `data/fireos/settings.txt`. The wake word and
voice settings are in `settings.alexa.txt` and only apply with `ALEXA=0`. Fire OS
rewrites some keys at boot, which is one reason `debloat.sh` is safe to run again.

## 3. App-ops and network policy, for the packages Amazon protects

The shell cannot disable them, but it can take away what they need:

```sh
appops set <package> RUN_IN_BACKGROUND ignore
appops set <package> RUN_ANY_IN_BACKGROUND ignore    # Android 9 and later
cmd netpolicy set restrict-background true           # Data Saver
cmd netpolicy add restrict-background-blacklist <uid>
```

Their background services are held in standby and their background data is
refused while Data Saver is on. A process that is already running keeps running,
and `am force-stop` is refused for them, so they are starved rather than stopped.
`lockdown.sh` applies this to `data/fireos/protected.txt`. `debloat.sh` applies the
same treatment to disabled packages whose process survived the disable.

## 4. DNS: what actually stops the uploads

dm-verity keeps `/system` read-only, so there is no hosts file to edit. Instead
[personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter) runs on the TV
as a local VPN. Every lookup goes through it, and listed names answer `127.0.0.1`.

The list is built on your computer and pushed to the TV as one file:

- the [HaGeZi](https://github.com/hagezi/dns-blocklists) Amazon tracker list,
  downloaded fresh each time,
- `data/blocklists/extra-hosts.txt`: telemetry, update and ad servers read out of
  Fire OS's own system apps,
- `data/blocklists/alexa-hosts.txt`, only with `ALEXA=0`.

With `PRIME=1` (the default), Prime Video's video CDN and Amazon's push service
are left out, because Prime Video does not start without them. The TV never
downloads a list itself, so it cannot end up with a half-written one.

Three settings keep the filter in the path:

- `routeIPs = <gateway>` puts the router's resolver inside the tunnel, so an app
  that asks the router directly is still filtered.
- `detectDNS = false` with an explicit `fallbackDNS` stops the filter falling out
  of the path when the TV changes network. That fallback is the router. Set
  `DNS_UPSTREAM` in `dumbtv.conf` only when those unknown names should go to
  other resolvers instead; a public blocklist resolver then sinks hosts this
  list deliberately left alone.
- `always_on_vpn_app` and a Doze exemption bring it back after a reboot. The app's
  own boot start is a second way back, and the only one on Fire OS 6, which forgets
  the always-on setting at boot.

On Fire OS 8 adb cannot write the app's folder, so the list goes to
`/sdcard/Android/media/` and you select it in the app once.

## 5. The home screen

On Android TV, `cmd package set-home-activity` changes the home app. On Fire OS it
prints `Success` and changes nothing: HOME still resolves to
`com.amazon.tv.launcher`, which Amazon protects, as it does
`com.amazon.firehomestarter`.

So dumbtv home ([launcher/](../launcher/README.md)) watches for Amazon's home
instead and replaces it.

**Fire OS 7 and 8: an accessibility service, `Guard`.** It receives window changes
only and reads no screen content.

- The HOME key never reaches an accessibility key filter on Fire OS, so `Guard`
  reacts to Amazon's home window appearing.
- Android holds back app launches for 5 s after HOME (`APP_SWITCH_DELAY_TIME`),
  which is why other launchers flash Amazon's home for seconds. `Guard` launches
  through an alarm `PendingIntent`, which `system_server` sends and which is exempt
  from the delay. `launcher.sh` sets
  `settings global alarm_manager_constants min_futurity=0` so the alarm fires at
  once.
- A plain overlay covers Amazon's home before it draws and is removed once dumbtv
  home is on screen. HOME from an app shows about 0.3 s of black.
- Fire OS 8 (Android 11) only lets an app start an activity while it has a visible
  window. The cover is that window, so `Guard` fires the launch after the cover has
  drawn. No permission is involved.

**Fire OS 6: a foreground service, `Keeper`.** Fire OS 6.7 refuses third-party
accessibility services. `Keeper` reads the foreground app from UsageStats and calls
`moveTaskToFront` when Amazon's home appears. That is not an activity launch, so
the 5 s delay does not apply. It is the only reason the launcher asks for any
permission at all, and those three permissions are capped at Android 8.1, so Fire
OS 7 and 8 see none.

Holding HOME still opens Fire OS's quick menu, and the launcher's Options can open
Amazon's home on purpose.

### HDMI inputs

An input tile hands Android's passthrough URI to the TV's own app, unless that app
is Amazon's Live TV, which claims every input and then shows its settings. In that
case the launcher shows the input itself with Android's public `TvView`: it asks
the TV's input service for a session and gives it the screen. Options › Open inputs
with chooses between the two; **Automatic** is the default.

## Not used, and why

| Not used | Why |
|---|---|
| `pm uninstall` for Amazon packages | disabling is enough and always reversible |
| `pm hide`, `pm suspend` | the shell lacks `MANAGE_USERS` and `SUSPEND_APPS` |
| `pm clear` | refused for protected packages |
| `dpm set-device-owner` | refused once the TV has an account |
| a hosts file | dm-verity keeps `/system` read-only |
| root | no supported unlock exists; see [WHY-NOT-REPLACE-OS.md](WHY-NOT-REPLACE-OS.md) |
