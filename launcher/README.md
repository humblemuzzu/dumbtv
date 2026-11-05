# dumbtv home

A home screen for Fire TV that does one job: show your apps and your HDMI inputs.

<p align="center">
  <img src="../docs/assets/screens/home.png" alt="dumbtv home: a clock, an Apps row and an Inputs row" width="720">
</p>

- **95 KB.** Plain Java and Android views. No AndroidX, Kotlin, Compose, Play
  Services or any other library.
- **No network.** There is no `INTERNET` permission, so Android will not let it
  open a connection.
- **One permission on Fire OS 7 and 8: run at startup.** personalDNSfilter only
  takes a restart request from an app holding it, and dumbtv home uses it for
  nothing else: when Android kills the filter under memory pressure, dumbtv home
  starts it again (on HOME, at boot and every 15 minutes). Fire OS 6 adds usage
  access and task order for the Keeper below, both limited to Android 8.1 and
  earlier. Check: `aapt2 dump permissions dumbtv-home.apk`.
- **No ads, no recommended rows, no account, no analytics, no update checks.**
- About 60–70 MB of memory with the home screen showing, about 37 MB while an app
  plays (measured on a Fire TV Edition television with 1.4 GB of RAM).

## Install

You need `adb` and a TV with ADB debugging on.

```sh
adb connect <tv-ip>:5555         # accept "Allow USB debugging?" on the TV
./launcher/install.sh            # download, check the SHA-256, install, take over HOME
```

The APK comes from this repository's
[releases](https://github.com/humblemuzzu/dumbtv/releases/latest), and `install.sh`
refuses it if the checksum differs from the one in `version.env`. Installing the
APK by hand works, but HOME only switches over once `install.sh` (or
`scripts/launcher.sh`) has set up the HOME hook described below.

Undo: `./launcher/install.sh --undo` gives HOME back to Amazon's launcher.

## Using it

| Remote | Does |
|---|---|
| OK | open the app, or switch to the input |
| Hold OK, or ☰ Menu | Move, Rename, Icon (with an icon pack chosen), Hide, App info |
| With Child lock on: hold ☰ Menu for 3 s | Options |
| Move mode | ◀ ▶ moves the tile, ▲ ▼ moves it a line in the grid, any other key drops it |
| Up from the top row | Options and Settings |
| Back on the home screen | jump to the first tile |
| On an input: Back | back to the home screen |
| On an input: ☰ Menu | show the input's name again |
| Hold HOME | Fire OS quick menu (Amazon's, unchanged) |

**Settings on Fire OS 6** opens a short list, because Amazon's own settings page draws its first row
off the top of the screen there (#77). The list has the sections an app other than Amazon's may open
directly: Notifications, Network, Applications, Equipment Control, Controllers & Bluetooth Devices
and Device & Software. Any of them the TV lacks is left out. Below them, **All settings…** opens
Amazon's settings row for the rest (Display & Sounds, Preferences, Accessibility and more), which need
Amazon's own signature to open. Like Options › Open Amazon's home screen, that stays on Amazon's home until
you open an app. On Fire OS 7 and 8 the Settings button opens Amazon's settings page as before.

**Options** cycles each setting with ◀ ▶ or OK:

| Setting | Choices |
|---|---|
| Accent | white, blue, cyan, green, amber, red, pink, violet |
| Tiles | small, medium, large |
| Layout | rows, or a grid that wraps to as many columns as fit |
| App order | your order, or recently used: the apps row re-sorts on each return home, newest first. It counts apps opened from dumbtv home, plus apps opened any other way (voice, another app) on Fire OS 6, where the launcher already has usage access. Apps never opened keep your order after the rest |
| All apps section | hidden (default), shown: a separate alphabetical list of every launchable app below your pinned apps. Child lock hides this section |
| App names | on focus, always, never |
| Clock | system, 24-hour, 12-hour |
| Memory meter | hidden, shown: free memory under the date, with a bar of what is in use; amber, with "running low", when Android reports low memory or under 15% is free. It only shows the numbers: it closes and frees nothing, and needs no permission |
| Inputs row | shown, hidden |
| Open inputs with | Automatic, the TV's app, dumbtv home (see below) |
| Background | black, graphite, midnight, image, adaptive, adaptive tint |
| Image dim, wallpaper rotation | for image backgrounds |
| Child lock | off, on: hides Options and Settings and turns off Hold OK and ☰ Menu on tiles, so the home screen only opens apps and inputs (see below) |
| App tile style | original (default), Afterglow: uppercase names inside the tile, a brand-color fade into black, a grayscale installed app icon in the corner, and a thin focus border |
| Library banner | shown (default), hidden. It only appears once a library folder holds titles (see below) |
| Icon pack | off, or an installed icon pack; reads "None installed" until one is ([Icon packs](#icon-packs)) |

Below them: Next wallpaper, which apps and inputs appear on the home screen, a
reset for order, names, picked icons and hidden tiles, and **Open Amazon's home screen**. Apps
without a TV banner, like the Appstore, start hidden; tick them to add them.

**All apps** is optional. Turn on Options › All apps section to keep the current
Apps section as **Pinned apps** and add **All apps** below it, in either rows or
grid layout. The full list includes unpinned apps and apps without TV banners;
apps with no launcher entry are not listed. Hold OK or press Menu on an app there
to Pin or Unpin it, Rename it, or open App info. The full list stays alphabetical;
your custom or recently-used order applies to pinned apps only. Options › Apps on
home screen still selects those pins. Turning the section off preserves them.

Up and down move between nonempty sections and remember a separate focused tile
in each. Back and HOME return to the first pinned app, or the first app in All
apps if nothing is pinned. Child lock hides All apps and disables tile menus, so
apps hidden from the pinned section stay hidden from this screen while it is locked.

**Afterglow** changes app tiles only, in both rows and grid layouts. Select it in
Options or set `tile_style=afterglow` in `home.conf`. The name remains visible inside
each tile regardless of the App names setting; input tiles are unchanged.
Known apps use locally defined colors; other apps use the selected accent.
Corner icons come from installed apps, so no brand-logo files or downloads are
needed. Missing icons still leave the name visible. Switching back to Original
restores the normal banners and App names behavior. Background and layout remain
separate choices.

| Grid layout | Adaptive background | Adaptive tint |
|---|---|---|
| ![Grid layout](../docs/assets/screens/grid.png) | ![Adaptive background](../docs/assets/screens/adaptive.png) | ![Adaptive tint](../docs/assets/screens/tint.png) |

## HDMI inputs

**Options › Open inputs with** decides what an input tile does:

- **Automatic** (default): the TV's own app, when exactly one app other than
  Amazon's Live TV claims the input; otherwise the launcher's own screen. Amazon's
  Live TV claims every input and then opens its settings or a channel scan.
- **The TV's app**: always hand the input to the TV's app, as releases up to 1.1
  did.
- **dumbtv home**: always use the launcher's own screen. It uses Android's public
  `TvView`: the picture, the input's name for a moment, and "No signal" when nothing
  is connected. Back returns to the home screen, and HOME releases the input at
  once. Nothing new is requested to do this.

## Child lock

For a TV children use. **Options › Child lock › On** hides the Options and Settings
buttons and turns off Hold OK and ☰ Menu on tiles, so nobody can move, rename or hide
a tile, or reach App info, from the home screen. Apps and inputs open as usual.

To change something, **hold ☰ Menu for 3 seconds** on the home screen: Options opens,
and Child lock › Off brings everything back. A short press does nothing. Over adb,
`lock=off` in `home.conf` does the same.

It keeps small hands out of dumbtv home; it is not a PIN, and it cannot lock what
Fire OS owns. Holding HOME still opens Fire OS's own quick menu (on a Fire TV Edition
TV: Inputs, Apps, Picture, Sound, Sleep Timer), and a remote's app buttons, or its
Settings button where it has one, still work. A PIN on dumbtv's buttons would leave
those doors open just the same. Fire OS has its own Parental Controls
under Settings › Preferences, which use your Amazon PIN; dumbtv leaves them alone.

## Icon packs

dumbtv home can show your apps with the pictures of an installed icon pack: TV icon
packs in the ADW/Nova format, like those made for launchers such as Projectivy.
Nothing changes until you install one; dumbtv itself ships and downloads no icons.

1. Install the pack, for example with Downloader or `adb install`.
2. Pick it under **Options › Icon pack**. Until a pack is installed, the row reads
   "None installed".

Each app then gets the pack's picture, found by the app's activity, then its package,
then a picture named after the app (`YouTube` → `youtube`). That last step finds icons
in TV packs mapped for Google TV package names, which Fire TV apps do not share. Apps
the pack has nothing for keep their own art. The pack's banners fill the tile.

**One app's icon.** Hold OK (or press ☰ Menu) on its tile and pick **Icon**. The picker
shows every icon of the chosen pack, those named like the app first; type in the filter
to narrow it down. **Automatic** goes back to the pack's choice; **App's own picture**
keeps the app's art. The choice is saved in `home.conf` as
`icon.<package>=<pack>/<icon>`.

**Switching packs.** A picked icon belongs to its pack and only counts while that pack
is chosen. Pick another pack, or Off, and every tile follows it; nothing needs fixing by
hand. Go back to the first pack and its picked icons return. If the chosen pack is
uninstalled, the tiles show their own art and the row shows Off until it is installed
again.

**What a pack is.** An app that answers a launcher's theme request
(`org.adw.launcher.THEMES` or `com.novalauncher.THEME`) and carries an appfilter, the
list that maps apps to its pictures. Both are needed: themes and wallpaper apps answer
the request too, but have no appfilter.

**Nothing of the pack runs.** dumbtv home only reads its pictures, and still has no
internet permission. You never need to open the pack's own app, and you can switch it
off; its pictures still work:

```sh
adb shell pm disable-user --user 0 <pack package>
```

The icons are the pack author's, under the pack's licence.

## The settings file

Everything lives in one text file on the TV:

```
/sdcard/Android/data/tv.dumbtv.home/files/home.conf
```

```ini
# white blue cyan green amber red pink violet, or #RRGGBB
accent=blue
# small medium large
tiles=medium
# rows grid
layout=rows
# custom recent (recent = newest first; the history stays in the app's private storage)
order=custom
# off on (Child lock hides this section)
all_apps=off
# focus always never
labels=focus
# auto 24h 12h
clock=auto
# off on
memory=off
# on off
inputs=on
# auto tv home
switch=auto
# black graphite midnight image adaptive adaptive-tint
background=black
# 0 20 40 60 80, image backgrounds only
dim=40
# off visit hourly daily daytime (visit = every return to the home screen)
rotate=off
# off on
lock=off
# off, or an installed icon pack's package
icon_pack=off
icon.com.stremio.one=com.example.iconpack/stremio
apps.order=com.amazon.firetv.youtube,org.videolan.vlc
apps.hide=org.videolan.vlc
apps.show=com.amazon.venezia
inputs.hide=com.droidlogic.tvinput/.services.AV1InputService/HW1
name.com.droidlogic.tvinput/.services.Hdmi2InputService/HW6=PS5
```

Comments go on their own line. Back it up with `adb pull`, restore it with
`adb push`; changes apply the next time the home screen comes back.

### Wallpapers

No permission is needed: the launcher reads its own folder.

The quickest way, on any Fire OS, is `scripts/wallpaper.sh` from the computer. It crops
each image to the TV's screen and scales it to its size when ImageMagick (`magick`) is
installed, pushes the images to the media folder below, and turns the image background
on:

```sh
./scripts/wallpaper.sh ~/Pictures/*.jpg                              # one or several
./scripts/wallpaper.sh --rotate hourly --dim 20 --shuffle *.png      # rotate in a random order
./scripts/wallpaper.sh --replace --crop top portrait.jpg             # drop the old ones first
```

By hand:

- **One wallpaper:** push it as `wallpaper.jpg` (or `.jpeg`, `.png`, `.webp`) into
  the folder above and set `background=image`.
- **Several:** push them into a `wallpapers/` subfolder, with any names, and pick a
  rotation. While that folder has images, `wallpaper.jpg` is ignored.

```sh
adb shell mkdir -p /sdcard/Android/data/tv.dumbtv.home/files/wallpapers
adb push *.jpg /sdcard/Android/data/tv.dumbtv.home/files/wallpapers/
```

### Library banner

A wide banner above the apps row for a few titles the owner picks: the picture
cross-fades to the next title every 8 seconds with a slow zoom. Up from the apps row
selects it, ◀ ▶ steps through the titles and OK opens the title in its app. Nothing is
downloaded: the pictures and the list are pushed from a computer, like wallpapers.

`library/library.conf` in the settings folder lists one title per line, and the
pictures sit beside it:

```
# image | title | app package | deep link (optional) | key=value extras (optional)
castle.jpg   | Castle   | com.hulu.plus     | https://www.hulu.com/series/castle-30fb4e12-5a72-4fe6-85ff-ddb5e88d1a65
seinfeld.jpg | Seinfeld | com.netflix.ninja | https://www.netflix.com/title/70153373
```

- The deep link is sent to that app only. When the app does not answer it, the app
  opens on its own home screen. Lines whose picture is missing, or whose app is not
  installed, are skipped.
- Links that open the title's page, checked on a Fire TV Stick (AFTSSS, Fire OS 7):
  Netflix `https://www.netflix.com/title/<id>`, Hulu `https://www.hulu.com/series/<slug-id>`,
  HBO Max `https://play.hbomax.com/show/<id>` and `/movie/<id>` (the id from an
  hbomax.com address), Paramount+ `https://www.paramountplus.com/shows/<name>/` and
  `/movies/video/<id>/`. `adb shell cmd package resolve-activity -a
  android.intent.action.VIEW -d <link> <package>` shows whether an app takes a link.
- Wide pictures work best; the banner crops to about 3.8 : 1. Only the picture on
  screen and the one fading in are held in memory.

`scripts/library.sh <folder>` takes a folder with `library.conf` and the pictures,
crops each picture to the banner on macOS (`sips`; an optional `crop.conf` of
`image fraction` lines moves the crop, 0 top to 1 bottom), replaces the TV's library
folder and returns to the home screen so the banner reloads.

### Fire OS 8: where adb can write

On Fire OS 8 (Android 11) `/sdcard/Android/data/<app>` is closed to `adb`: listing,
reading and pushing all fail with `Permission denied`, so none of the commands above
work there. The launcher also looks in its own media folder, which `adb` can still
write:

```
/sdcard/Android/media/tv.dumbtv.home/home.conf
/sdcard/Android/media/tv.dumbtv.home/wallpapers/
```

```sh
adb shell mkdir -p /sdcard/Android/media/tv.dumbtv.home/wallpapers
adb push *.jpg /sdcard/Android/media/tv.dumbtv.home/wallpapers/
adb push home.conf /sdcard/Android/media/tv.dumbtv.home/home.conf
```

Wallpapers are read from both folders, in name order. A settings file pushed there
is applied once, the next time the home screen comes back, and wins over what the TV
has; what was applied is remembered, so the same push is not applied twice and a
later change in Options is not overwritten by it.

To back the settings up there, pick Options › Export settings for adb pull. It
writes a copy named `home-export.conf`, which the launcher never reads, so it is
not taken for a push. Push it back as `home.conf` to restore it:

```sh
adb pull /sdcard/Android/media/tv.dumbtv.home/home-export.conf home.conf
adb push home.conf /sdcard/Android/media/tv.dumbtv.home/home.conf
```

`rotate=daytime` matches wallpapers to the time of day by name: `morning` or `day`
(06:00 to 13:00), `afternoon` or `evening` (13:00 to 20:00), `night` (the rest), as
in `morning-coast.jpg`.

`background=adaptive` takes a dark colour from the focused app's artwork.
`background=adaptive-tint` keeps the wallpaper and adds a light wash of that
colour; without a wallpaper it shows graphite.

## How HOME works on Fire OS

Fire OS has no working "default launcher" setting, and Amazon's launcher is a
protected package. Measured on a Fire OS 7 television:

1. **The HOME key cannot be intercepted.** Fire OS handles it before any
   accessibility key filter runs. So `Guard`, a small accessibility service, waits
   for Amazon's home window instead. It sees window changes only and reads no
   screen content.
2. **Android delays app launches for 5 s after HOME** (`APP_SWITCH_DELAY_TIME`),
   which is why other launchers flash Amazon's home. A launch fired by
   AlarmManager comes from `system_server` and is exempt.
   `install.sh` sets `min_futurity=0` so the alarm fires at once; `--undo` removes
   it. Up to Android 11 that is `settings global alarm_manager_constants`; Android
   12 and later ignore it and read `device_config` namespace `alarm_manager`.
3. **Amazon's home is covered before it draws.** `Guard` keeps an overlay ready,
   with no surface while idle so it costs nothing during video, and removes it once
   dumbtv home is on screen.
4. **Fire OS 8 (Android 11) only lets an app with a visible window start an
   activity.** The cover is that window, so `Guard` launches once it has drawn.

HOME from an app shows about 0.3 s of black, then this home screen.

### Fire OS 6

Fire OS 6.7 refuses third-party accessibility services, so it uses `Keeper`, a
small foreground service:

1. UsageStats (`appops set tv.dumbtv.home GET_USAGE_STATS allow`, granted by
   `install.sh`) tells it when Amazon's launcher comes to the front.
2. `ActivityManager.moveTaskToFront` brings dumbtv home back. That is not an
   activity launch, so the 5 s delay does not apply: under 0.25 s with a real
   remote.
3. At boot there is no dumbtv task yet, so the first start uses an alarm.

**Options › Open Amazon's home screen** pauses the takeover until another app is
opened.

## Build it yourself

Needs JDK 17 and the Android SDK (`platforms;android-25`, `build-tools`). There is
no Gradle: `build.sh` runs aapt2, javac, d8, zipalign and apksigner directly, and
compiles against Android 7.1 so one APK runs on Fire OS 6, 7 and 8.

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
sdkmanager "platforms;android-25" "build-tools;34.0.0"
./launcher/build.sh              # launcher/build/dumbtv-home.apk
./launcher/install.sh --build    # build, install, take over HOME
```

On Linux, set `ANDROID_HOME`; `build.sh` finds a JDK 17 in
`/usr/lib/jvm/java-17-openjdk` (Arch: `pacman -S jdk17-openjdk`). It prefers JDK 17
because build-tools 34's `d8` has crashed on classes from newer JDKs.

Your build is signed with a key `build.sh` creates on first run, and Android will
not update the release with it or the other way round. `install.sh` says so; back
up `home.conf`, then `adb uninstall tv.dumbtv.home`.

## Diagnostics

Logcat has everything under the tag `dumbtvtest`. For problems at boot, when the
log has rotated by the time you can read it, the Keeper can also write a file that
survives reboots:

```sh
adb shell touch /sdcard/Android/data/tv.dumbtv.home/files/debug
```

While that file exists, log lines go to `files/keeper.log` in the same folder.
Delete both to stop.

## Files

| File | What it is |
|---|---|
| `src/.../Home.java` | the home screen: clock, Apps row, Inputs row, tile menu, move mode |
| `src/.../Tile.java` | one tile, drawn in a single `onDraw`: banner or icon, focus ring |
| `src/.../Grid.java` | the grid layout |
| `src/.../FocusColor.java` | the colour for the adaptive backgrounds |
| `src/.../Catalog.java` | which apps and inputs exist, and their order |
| `src/.../Item.java` | one app or input |
| `src/.../Input.java` | the built-in input screen (`TvView`) |
| `src/.../Config.java` | reads and writes `home.conf` |
| `src/.../Options.java` | the settings screen |
| `src/.../IconPack.java` | finds installed icon packs and reads their pictures |
| `src/.../IconPicker.java` | the screen that picks one app's icon |
| `src/.../IconMatch.java` | the icon pack text rules, kept free of Android for the host test |
| `src/.../Ui.java` | shared colours, text and sizes |
| `src/.../Guard.java` | the Fire OS 7 and 8 HOME takeover |
| `src/.../Keeper.java` | the Fire OS 6 HOME takeover |
| `src/.../Recent.java` | when each app was last opened, for App order › Recently used |
| `src/.../Hero.java` | the library banner: cross-fade, slow zoom, ◀ ▶ and OK |
| `src/.../Library.java` | reads `library/library.conf` and opens a title in its app |
| `src/.../BootReceiver.java` | restarts the Keeper after a boot or an update |
| `build.sh` | aapt2, javac, d8, zipalign, apksigner |
| `install.sh` | download and checksum (or `--build`), install, HOME hook, `--undo` |
| `version.env` | the version and the release's SHA-256, read by the three scripts |
| `release-check.sh` | checks that the version, the built APK and the GitHub release agree |
| `tests/FocusColorTest.java` | host-side test for `FocusColor`, run by CI |
| `tests/RecentTest.java` | host-side test for the Recently used order, run by CI |
| `tests/LibraryTest.java` | host-side test for `library.conf` parsing, run by CI |
| `tests/ConfigTest.java` | file import and failed-write recovery tests, run by CI |
| `tests/AppSectionsTest.java` | pinned and All apps selection tests, run by CI |
| `tests/IconMatchTest.java` | host-side test for the icon pack text rules, run by CI |

## Releasing

1. Bump `VERSION`, `VERSION_CODE` and `VERSION_NAME` in `version.env`.
2. `./build.sh`, copy `build/dumbtv-home.apk` to `build/dumbtv-home-<VERSION>.apk`,
   and put its SHA-256 in `version.env`.
3. Upload it as `dumbtv-home.apk` to a release tagged `launcher-v<VERSION>`.
4. `./launcher/release-check.sh` fails if the tag, the APK and the hash disagree.
   CI runs `--manifest` on every push.
