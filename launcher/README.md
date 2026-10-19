# dumbtv home

A home screen for Fire TV that does one job: show your apps and your HDMI inputs.

- **45 KB APK.** Plain Java and Android views: no AndroidX, Kotlin, Compose, Play
  Services or other libraries.
- **No network.** It has no `INTERNET` permission, so Android blocks it from
  opening any network connection. The APK declares usage access, reorder-tasks
  and boot-completed with `android:maxSdkVersion="27"`: Fire OS 6 requests them
  for the Keeper described below, Fire OS 7/8 does not. Check it yourself:
  `aapt2 dump permissions build/dumbtv-home.apk`.
- **No ads, no "recommended" rows, no account, no analytics, no update checks.**

## Install (no Java, no SDK)

You need `adb` and a TV with ADB debugging on (Settings → My Fire TV → About →
click the device name 7 times, then Developer Options → ADB debugging).

```sh
adb connect <tv-ip>:5555         # accept "Allow USB debugging?" on the TV
./launcher/install.sh            # downloads the APK, checks its SHA-256, installs, takes over HOME
```

That is all. The APK comes from this repo's
[releases](https://github.com/humblemuzzu/dumbtv/releases/latest); the script
refuses to install it if the checksum differs from the one pinned in
`install.sh`. Prefer to install by hand? Download `dumbtv-home.apk` from the
release and `adb install` it — but HOME only switches over after `install.sh`
(or `scripts/launcher.sh`) has installed the HOME hook described below: the
accessibility service and alarm setting on Fire OS 7/8, the UsageStats Keeper on
Fire OS 6.

Undo: `./launcher/install.sh --undo` gives HOME back to Amazon's launcher.

## Why not Projectivy

This repo used to install Projectivy. It was replaced because it is closed source
and asks for far more than a home screen needs: the advertising ID, ad
attribution, Play install referrer, internet, notification access and package
install/delete. Measured on an Onida Fire TV Edition (Fire OS 7.7, 1.4 GB RAM):

| | dumbtv home | Projectivy 4.72 |
|---|---|---|
| APK | 45 KB | several MB |
| Permissions requested | 0 (Fire OS 7/8); usage access (Fire OS 6) | 20+, incl. advertising ID |
| RAM, home on screen | 57–72 MB PSS | 135 MB PSS |
| RAM, while an app plays | ~37 MB PSS | — |
| HOME → home screen | 0.16–0.25 s | 3–6 s, Amazon's home shows first |

## Build it yourself

Needs a JDK 17 and the Android SDK (`platforms;android-28`, `build-tools`). No
Gradle: `build.sh` calls aapt2, javac, d8, zipalign and apksigner directly.

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
sdkmanager "platforms;android-28" "build-tools;34.0.0"
./launcher/build.sh              # → launcher/build/dumbtv-home.apk
./launcher/install.sh --build    # build, install, take over HOME
```

On Linux set `JAVA_HOME` and `ANDROID_HOME`; the defaults are Homebrew's paths.

`build.sh` creates `launcher/release.keystore` the first time it runs. Your build
is signed with your key, the release with the project's, and Android will not
update one with the other: `install.sh` says so and tells you to
`adb uninstall tv.dumbtv.home` first (back up `home.conf` before, see below).

## Using it

| Remote | Does |
|---|---|
| OK | open the app, or switch to the input |
| Hold OK, or ☰ Menu | Move, Rename, Hide, App info |
| Move mode | ◀ ▶ moves the tile (in the grid, ▲ ▼ move it a line), any other key drops it |
| Up from the top row | Options and Settings |
| Back on the home screen | jump to the first tile |
| Hold HOME | Fire OS quick menu (Amazon's, unchanged) |

**Options** cycles each setting with ◀ ▶ or OK: layout, accent color, tile size, app names
(on focus / always / never), clock format, inputs row, background (black, graphite,
midnight, or an image), image dim and wallpaper rotation, and **Next wallpaper**
steps to the next image. It also lists which apps and inputs appear on
the home screen. Apps without a TV banner, like the Appstore or Downloads, start
hidden; tick them there to add them.

**Layout › Grid with top shelf** is the Apple TV arrangement: the focused tile's art
shows large on a shelf at the top, over a soft blur of the same art, and apps fill a
grid below it (tile size picks 6, 5 or 4 per line). Focus lifts a tile on a shadow
instead of drawing a ring. Moving past the first line scrolls the shelf away, so more
lines fit on screen. The blur is the art drawn at 16×9 and scaled up, so it needs no
library and no network. **Rows** is the original single row of apps.

## The settings file

Everything is stored in one plain text file on the TV:

```
/sdcard/Android/data/tv.dumbtv.home/files/home.conf
```

```
# rows grid
layout=rows
# white blue cyan green amber red pink violet, or #RRGGBB
accent=blue
# small medium large
tiles=medium
# focus always never
labels=focus
# auto 24h 12h
clock=auto
# on off
inputs=on
# black graphite midnight image
background=black
# 0 20 40 60 80, image background only
dim=40
# off visit hourly daily daytime (visit = every return to the home screen)
rotate=off
apps.order=com.amazon.firetv.youtube,org.videolan.vlc
apps.hide=org.videolan.vlc
apps.show=com.amazon.venezia
inputs.hide=com.droidlogic.tvinput/.services.AV1InputService/HW1
name.com.droidlogic.tvinput/.services.Hdmi2InputService/HW6=PS5
```

Comments go on their own line; anything after `=` is the value.

Back it up with `adb pull`, restore it with `adb push`. Hand edits apply the next
time the home screen comes back. For a wallpaper, push `wallpaper.jpg` (or `.png`)
into the same folder and set `background=image`. For several, push them into a
`wallpapers/` subfolder instead (`.jpg`, `.png` or `.webp`, shown in name order) and
pick a rotation; while that folder has images, `wallpaper.jpg` is ignored.

```sh
adb shell mkdir -p /sdcard/Android/data/tv.dumbtv.home/files/wallpapers
adb push *.jpg /sdcard/Android/data/tv.dumbtv.home/files/wallpapers/
```

`rotate=daytime` matches wallpapers to the time of day: name a file with `morning` or `day`
(06:00–13:00), `afternoon` or `evening` (13:00–20:00), or `night` (the rest), for example
`morning-coast.jpg` or `night-city.jpg`. Several files for one period take turns on each
return home. With no file named that way, the period simply picks one image of the folder.

## How HOME works on Fire OS

Fire OS 7 has no working "default launcher" setting, and Amazon's launcher is a
protected package. Three things combine to make HOME land here, measured on this TV:

1. **The HOME key can't be intercepted.** Fire OS handles it before any accessibility
   key filter runs (D-pad keys reached the filter; HOME never did). So `Guard`, a
   small accessibility service, watches for Amazon's home window instead. It reads
   no screen content and sees only window changes.
2. **Android delays app launches for 5 s after a HOME press** (`APP_SWITCH_DELAY_TIME`).
   This is what makes other launchers flash Amazon's home for 3–6 s. A launch fired
   by AlarmManager comes from `system_server`, which is exempt from that delay.
   AlarmManager normally refuses to fire sooner than 5 s ahead, so `install.sh` sets
   `settings global alarm_manager_constants min_futurity=0`. `--undo` removes it.
3. **Amazon's home is covered before it draws.** `Guard` keeps an overlay window ready
   (no surface while idle, so it costs nothing during video). It shows the overlay
   the moment Amazon's home appears, and removes it once our window is on screen.

What you see on HOME: the app, then about 0.3 s of black, then this home screen.

### Fire OS 6

Fire OS 6.7 goes further: its `A11yAuthorizer` refuses third-party accessibility
services, so `Guard` is never bound and HOME cannot be taken over that way. The
Fire OS 6 build therefore uses `Keeper`, a small foreground service that needs no
accessibility:

1. `UsageStats` (`appops set tv.dumbtv.home GET_USAGE_STATS allow`, granted by
   `install.sh`) tells it when Amazon's launcher reaches the foreground.
2. `ActivityManager.moveTaskToFront` brings the dumbtv task back. This is not an
   activity launch, so it is not held back by the 5 s app-switch delay: measured
   under 0.25 s with a real remote key.
3. At boot there is no dumbtv task yet, so the first start uses an alarm-fired
   `PendingIntent`; after that, `moveTaskToFront` takes over.

`Options > Open Amazon's home screen` pauses the Keeper, so Amazon's home stays
until another app is opened.

## Files

| File | What it is |
|---|---|
| `src/.../Home.java` | the home screen: clock, Apps row or grid with top shelf, Inputs row, tile menu, move mode |
| `src/.../Tile.java` | one tile, drawn in a single `onDraw`: banner or icon, focus ring or lift |
| `src/.../Catalog.java` | which apps and inputs exist, and which are shown in what order |
| `src/.../Config.java` | the `home.conf` reader and writer |
| `src/.../Options.java` | the settings screen |
| `src/.../Guard.java` | the Fire OS 7/8 HOME takeover described above |
| `src/.../Keeper.java` | the Fire OS 6 HOME takeover (UsageStats + moveTaskToFront) |
| `src/.../BootReceiver.java` | restarts the Keeper after a boot or an update |
| `build.sh` | aapt2 → javac → d8 → zipalign → apksigner |
| `install.sh` | download + checksum (or `--build`), install, HOME hook (Guard or Keeper), `--undo` |
| `version.env` | the only place `VERSION` and `APK_SHA256` live; both scripts read it |
| `release-check.sh` | proves version, built APK and GitHub release agree (`--local`, `--remote`, `--manifest`) |

## Releasing

1. Edit `version.env` (bump `VERSION`, `VERSION_CODE`, `VERSION_NAME`).
2. `./build.sh`, then put the new SHA-256 in `version.env`.
3. Upload `build/dumbtv-home.apk` to a release tagged `launcher-v<VERSION>`.
4. `./launcher/release-check.sh` — it fails if the tag, the APK and the hash
   disagree. CI runs `--manifest` on every push.
