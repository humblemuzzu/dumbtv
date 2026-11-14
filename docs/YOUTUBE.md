# YouTube: no ads, no Shorts

The stock YouTube app on a Fire TV shows ads and Shorts. Neither can be blocked
from the network, and the reason is worth understanding before trying.

## Why DNS blocking cannot work here

A DNS filter (this repo's on-device one, a Pi-hole, any other) sees one
thing: the hostname being looked up. It can answer `softwareupdates.amazon.com`
with `127.0.0.1` because that host does nothing but updates.

YouTube does not work that way. Video and ads are served by **the same hosts**
(`*.googlevideo.com`), and in the server-side-inserted case, **stitched into the
same stream**. There is no separate host to block. Blocking `googleads.g.doubleclick.net`
removes some of the page-level advertising, but the pre-roll inside the player is
a request for a video segment from the same CDN as the video itself. Block the
CDN and the video is gone too.

That is why every "ad-free YouTube" list for Pi-hole circles back to "block the
ads on your phone app instead".

## How Brave does it, and why that does not transfer

Brave blocks YouTube ads because it *is the browser*. It sits between the page
and the network, so it can:

- refuse a request the player makes for an ad segment, while allowing the video;
- rewrite the player response before YouTube's own JavaScript parses it;
- strip the ad slots out of the rendered page.

All of that needs control of the web page. An installed TV app gives you no such
hook: the app talks to YouTube directly and renders whatever it receives. Nothing
outside the app can refuse one segment and allow another.

The practical conclusion: **you cannot fix the stock YouTube app. You replace it
with a client that never asks for the ads.**

## SmartTube

[SmartTube](https://github.com/yuliskov/SmartTube) is that client: open source
(MIT), made for Android TV, and it runs on every Android-based Fire TV. Its own FAQ is the clearest evidence it cannot show an ad:

> Can I whitelist ads on some channels? — No, this is not possible. SmartTube
> does not have any code to display ads.

It also has what this repo needs for a TV shared with children:

- **Hide shorts everywhere** — removes the Shorts shelf from Home, Subscriptions,
  search results and the watch page.
- **Hide Mixes**, **Hide watched videos from Home / Subscriptions** — quieter home.
- **Video presets** — cap the resolution. On a 1080p panel, asking for 4K costs
  CPU and memory for no visible gain, and many Fire TV televisions have slow
  32-bit chips.
- **SponsorBlock** — skips in-video sponsor segments, crowd-sourced.

## What the script does

Nothing here happens by default. `configure.sh` asks about it once; the answer is
saved as `YOUTUBE=0` unless you said yes, and `all.sh` only runs this when it is
1. Run it directly and it will refuse, and tell you how to opt in.

```sh
./scripts/youtube.sh              # install and switch over
./scripts/youtube.sh --remove     # ...and delete the stock app instead of disabling it
./scripts/youtube.sh --dry-run    # print the plan
./scripts/youtube.sh --force      # run it even though dumbtv.conf says no
```

1. Downloads the SmartTube release APK and verifies its SHA-256 against the pin
   in the script.
2. Installs it as `org.smarttube.stable`.
3. Disables the stock YouTube app (`com.amazon.firetv.youtube`) with
   `pm disable-user --user 0` — reversible, and it stays installed, so the
   Appstore can hand it back. `--remove` uninstalls it instead: it lives in
   `/data`, so that is a real deletion, and the Appstore is the way back.
4. Puts SmartTube in the dumbtv home app order and drops YouTube out of it. On
   Fire OS 8, adb cannot read or edit that order, so this step is skipped:
   SmartTube appears at the end of the apps row, and you move it by holding OK on
   it and picking Move.

The stock app has to be disabled, not just hidden: Amazon's launcher and any
voice-search intent still resolve to it otherwise, and a child who finds the "all
apps" list is back in the ad feed.

## The two settings ADB cannot write

SmartTube keeps its settings in its private storage, so the first configuration
is done on the TV with the remote. Once. It survives updates.

**Hide Shorts** — left drawer (press LEFT) → Settings → General → Hide content →
tick **Hide shorts everywhere**.

**Cap the resolution** — Settings → Player → Video presets →
**1080p 60fps vp9** on a 1080p TV. Take a vp9 entry, never `av01`: most TVs
before 2020 have no AV1 hardware decoder and the video shows "profile not
supported" or stutters badly.

Checked on a Fire TV Edition television (Fire OS 7.7.1.7): with *Hide shorts
everywhere* ticked there is no Shorts row, and playback makes no ad requests.

## Voice search

Amazon's voice search launches the stock YouTube app by package name. With it
disabled, "search YouTube for X" does nothing. SmartTube's README documents a
bridge APK (`kutt.to/stn_bridge_amazon`) that takes over the intent; it is not
installed by this script and it is not required for anything else.

## Undo

```sh
adb shell pm enable --user 0 com.amazon.firetv.youtube   # if it was disabled, it comes back
adb uninstall org.smarttube.stable                # SmartTube goes away
```

If it was removed with `--remove`, the only way back is the Amazon Appstore
(YouTube, free). That is the one step in this repo that is not undoable from the
command line — everything else is.

Then set the dumbtv home app order back in Options, or, on Fire OS 6 and 7, edit
`/sdcard/Android/data/tv.dumbtv.home/files/home.conf`.

## What this does not do

- **Not YouTube Premium.** No offline downloads, no background audio on the phone.
- **Not Kids mode.** SmartTube has a Kids section, but it is not the YouTube Kids
  app and it is not a parental-control system: recommendations are still YouTube's.
  For young children, the honest answer is the YouTube Kids app under Amazon's
  parental controls, or no YouTube on the TV at all.
- **Not for Fire TV devices that run VegaOS.** Amazon started shipping VegaOS, which
  is not Android, on some devices from October 2025, and no sideloaded app runs there.
  Other 2025 Fire TVs still run Android underneath (issue #57); `adb shell getprop
  ro.build.version.sdk` answering a number means it is Android. See the SmartTube
  README's device-support note.
