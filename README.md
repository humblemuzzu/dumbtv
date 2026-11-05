<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-dark.svg">
    <img src="docs/assets/logo-light.svg" alt="dumbtv" width="300">
  </picture>
</p>

<p align="center"><b>Your TV should do what you paid for.</b><br>
Ads, tracking and clutter out of your Fire TV. Your apps and HDMI inputs stay.</p>

<p align="center">
  <a href="https://dumbtv.org"><b>dumbtv.org</b></a> ·
  <a href="https://dumbtv.org/start">Get started</a> ·
  <a href="https://dumbtv.org/blocked">What it blocks</a> ·
  <a href="https://dumbtv.org/tv">Devices</a> ·
  <a href="https://dumbtv.org/help">Get help</a>
</p>

<p align="center">
  <a href="https://github.com/humblemuzzu/dumbtv/stargazers"><img src="https://img.shields.io/github/stars/humblemuzzu/dumbtv?style=flat&color=4C8DFF" alt="Stars"></a>
  <a href="https://github.com/humblemuzzu/dumbtv/releases/latest"><img src="https://img.shields.io/github/v/release/humblemuzzu/dumbtv?label=dumbtv%20home&color=4C8DFF" alt="Latest launcher release"></a>
  <a href="https://github.com/humblemuzzu/dumbtv/actions/workflows/validate.yml"><img src="https://github.com/humblemuzzu/dumbtv/actions/workflows/validate.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-4C8DFF" alt="MIT licence"></a>
  <a href="#thanks"><img src="https://img.shields.io/github/contributors/humblemuzzu/dumbtv?color=4C8DFF" alt="Contributors"></a>
</p>

<p align="center">
  <img src="docs/assets/hero.png" alt="dumbtv home on a television: a clock, an Apps row and an Inputs row" width="880">
</p>

<p align="center"><sub>As seen in
<a href="https://techcrunch.com/2026/10/19/people-are-using-ai-to-make-their-smart-tvs-dumb-again/">TechCrunch</a> ·
<a href="https://www.techradar.com/entertainment/streaming/your-tv-should-do-what-you-paid-for-people-are-vibe-coding-apps-to-banish-ads-from-their-smart-tvs-and-android-tv-and-its-catching-on">TechRadar</a> ·
<a href="https://www.tomsguide.com/ai/people-are-using-ai-to-turn-their-smart-tvs-into-dumb-tvs-and-you-might-want-to-do-the-same">Tom's Guide</a> ·
<a href="https://gizmodo.com/people-using-ai-to-make-smart-tvs-dumb-again-is-the-most-2026-thing-ever-2000828213">Gizmodo</a> ·
<a href="https://www.xda-developers.com/people-are-now-vibe-coding-their-smart-tvs-to-strip-them-of-ads-and-unwanted-features/">XDA</a> ·
<a href="https://itsfoss.com/news/debloat-smart-tv/">It's FOSS</a> ·
<a href="docs/PRESS.md">and more than 100 others</a></sub></p>

---

## The story

I had a Fire TV Edition television that had become painful to use. Menus lagged,
apps took ages to open, and the home screen was mostly ads. When I looked, it had
129 MB of free memory, and much of the rest went to apps that started on their own
and to Amazon services that report what you watch.

So I asked a coding agent to clean it up over ADB, and
[posted what happened](https://x.com/DevMuzzammil/status/2111595814099591535). People
asked for the scripts, so I put them here and
[shared them](https://x.com/DevMuzzammil/status/2111912547318395338). Since then,
owners of about twenty other TVs and sticks have run it and sent back what they
found.

dumbtv is that cleanup, made safe to run on someone else's TV.

## What it does

On a Fire TV stick, Cube or Fire TV Edition television, from your computer, with
no root:

- **Removes the ads and tracking.** Disables Amazon's sponsored rows, ad
  services, screen fingerprinting (ACR), telemetry and experiments, and turns off
  ad tracking and usage reporting.
- **Blocks what cannot be disabled.** A small open-source DNS filter on the TV
  stops Amazon's telemetry and automatic updates from reaching their servers.
  Everything else, including your streaming apps, works as before.
- **Replaces the home screen** with [dumbtv home](launcher/README.md): your apps,
  your inputs and a clock. 62 KB, open source, no internet access.
- **Leaves your apps alone.** Nothing you watch is removed unless you name it.
- **Can all be undone** with one command.

## Quick start

You need a computer with [`adb`](https://developer.android.com/tools/releases/platform-tools)
and about ten minutes. On the TV, turn on ADB: **Settings → My Fire TV → About**,
press OK on the device name 7 times, then **Developer Options → ADB debugging**.
The TV's IP address is under **Settings → My Fire TV → About → Network** (on a
Fire TV Edition television: **Settings → Network**, then the connected network).

**With a coding agent** (Claude Code, Codex, Cursor, Gemini and others): clone this
repository, open the agent in it, and say:

> Clean up my Fire TV. Its IP address is _(the address from the Network screen)_.

It reads [AGENTS.md](AGENTS.md), asks you a few questions, and runs the steps
below.

**By hand:**

```sh
git clone https://github.com/humblemuzzu/dumbtv && cd dumbtv
adb connect <tv-ip>:5555        # press Allow on the TV
./scripts/preflight.sh          # what the TV is and what would change; changes nothing
./scripts/configure.sh          # a few questions
./scripts/all.sh                # asks before each step, reboots the TV twice
./scripts/verify.sh             # checks the result
```

Works from macOS, Linux and Windows (WSL or Git Bash). The same steps, one at a
time, are at [dumbtv.org/start](https://dumbtv.org/start).

**Rather not do it yourself?** [Tell us about your TV](https://dumbtv.org/help) and
we will help.

## What you choose

`configure.sh` asks, and nothing is decided for you:

| Question | Default |
|---|---|
| Do you watch Prime Video? | yes: its servers stay reachable |
| Keep Alexa and the voice button? | yes |
| Replace Amazon's home screen with dumbtv home? | yes |
| Run the DNS filter on the TV? | yes |
| Any apps you want removed? | none |
| Replace the YouTube app with SmartTube (no ads, no Shorts)? | no |

Your answers are saved in `dumbtv.conf`; run `configure.sh` again to change them.

## What changes

| | |
|---|---|
| **Amazon packages** | About 50 disabled: ads, sponsored content, ACR, telemetry, crash and usage uploads, experiments, update nags. Disabled, never deleted. |
| **Protected packages** | The few Amazon will not let anyone disable lose background running; the DNS filter blocks what they upload. |
| **Settings** | Ad tracking limited, interest-based ads off, usage reporting off, home-screen autoplay off. |
| **DNS** | [personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter) runs on the TV and blocks about 400 Amazon telemetry, ad and update hosts. YouTube, the Appstore and your other apps are not blocked. |
| **Home screen** | dumbtv home instead of Amazon's: no promo tiles, no sponsored rows. |
| **Your apps** | Untouched. |

[How it works](docs/HOW-IT-WORKS.md) explains each part and why it is needed.
[dumbtv.org/blocked](https://dumbtv.org/blocked) lists every package, what it does
and what you lose without it.

## dumbtv home

| Rows | Grid | Options |
|---|---|---|
| ![Rows layout](docs/assets/screens/home.png) | ![Grid layout](docs/assets/screens/grid.png) | ![Options](docs/assets/screens/options.png) |

A home screen that shows your apps and your HDMI inputs, and nothing else. Accent
colours, tile sizes, rows or a grid, wallpapers, adaptive backgrounds, renaming and
hiding, all from the remote. HOME lands on it in a fraction of a second.

It is plain Java with no libraries and no internet permission, so it cannot send
anything anywhere. On Fire OS 7 and 8 its only permission is run at startup, used
to restart the DNS filter when Android kills it. It works
on its own, without the rest of dumbtv:

```sh
adb connect <tv-ip>:5555
./launcher/install.sh
```

[launcher/README.md](launcher/README.md) covers the settings, wallpapers and how
it takes over the HOME button.

## Tested on

Each row is a report from the person who ran it. **tested** means the whole pass ran
and `verify.sh` passed on that device. [dumbtv.org/tv](https://dumbtv.org/tv) has
every model with its menu paths and quirks.

| Device | Fire OS | Status | Tested by |
|---|---|---|---|
| [Fire TV Stick 4K, 1st gen (AFTMM)](catalog/devices/fireos-amazon-aftmm.yaml) | 6.7 | tested | [@niz-coder](https://github.com/niz-coder) |
| [Insignia Fire TV Edition (AFTEAMR311)](catalog/devices/fireos-insignia-afteamr311.yaml) | 6.7 | tested | [@gtalusan](https://github.com/gtalusan) |
| [Toshiba 50LF621U19, 55LF711U20 Fire TV Edition (AFTKMST12)](catalog/devices/fireos-toshiba-aftkmst12.yaml) | 6.7 | tested | [@ryanlucht](https://github.com/ryanlucht), [@zekesax](https://github.com/zekesax) |
| [Insignia NS-43DF710NA19 Fire TV Edition (AFTJMST12)](catalog/devices/fireos-insignia-aftjmst12.yaml) | 6.7 | tested | [@Muffy0906](https://github.com/Muffy0906) |
| [Fire TV, 3rd gen (AFTN)](catalog/devices/fireos-amazon-aftn.yaml) | 6.2 | tested | [@adi-c](https://github.com/adi-c) |
| [Grundig 50 UHD AGA Fire TV Edition (AFTEU014)](catalog/devices/fireos-grundig-afteu014.yaml) | 7.6 | tested | [@mzmonkey](https://github.com/mzmonkey) |
| [Fire TV Cube, 2nd gen (AFTR)](catalog/devices/fireos-amazon-aftr.yaml) | 7.7 | tested | [@podulator](https://github.com/podulator) |
| [Fire TV Cube, 3rd gen (AFTGAZL)](catalog/devices/fireos-amazon-aftgazl.yaml) | 7.7 | tested | [@sakalliAI](https://github.com/sakalliAI) |
| [Amazon AFTALMO](catalog/devices/fireos-amazon-aftalmo.yaml) | 7.7 | tested | [@TheiMacNoob](https://github.com/TheiMacNoob) |
| [Xiaomi L55M7-FVEU Fire TV (AFTANNA0)](catalog/devices/fireos-xiaomi-aftanna0.yaml) | 7.7 | tested | [@jmcerrejon](https://github.com/jmcerrejon) |
| [Fire TV Stick 4K Max, 1st gen (AFTKA)](catalog/devices/fireos-amazon-aftka-ps7713.yaml) | 7.7 | tested | [@harupong](https://github.com/harupong), [@Elv1sh](https://github.com/Elv1sh) |
| [Insignia 50" F30 (AFTDCT31)](catalog/devices/fireos-insignia-aftdct31.yaml) | 7.7 | tested | [@timcunningham-sv](https://github.com/timcunningham-sv), [@twwebb3](https://github.com/twwebb3) |
| [Onida 43" Fire TV Edition (AFTGRO001)](catalog/devices/fireos-onida-aftgro001.yaml) | 7.7 | tested | [@humblemuzzu](https://github.com/humblemuzzu) |
| [Fire TV Stick HD 2024, Stick Lite 2020 (AFTSS)](catalog/devices/fireos-amazon-aftss.yaml) | 7.7 | tested | [@RabeJonas](https://github.com/RabeJonas), [@Prathamesh913](https://github.com/Prathamesh913), [@arielvieira](https://github.com/arielvieira) |
| [Hisense 55QD65NF (AFTHA004)](catalog/devices/fireos-hisense-aftha004.yaml) | 7.7 | tested | [@stedrow](https://github.com/stedrow) |
| [Fire TV 4-Series 43" (AFTTI43)](catalog/devices/fireos-amazon-aftti43.yaml) | 7.7 | tested | [@ScottDavidCA](https://github.com/ScottDavidCA), [@dleslie](https://github.com/dleslie) |
| [TCL Fire TV (AFTSHN02)](catalog/devices/fireos-tcl-aftshn02.yaml) | 7.7 | tested | [@scaongithub](https://github.com/scaongithub) |
| [Ember 55, Fire TV Omni Mini-LED (AFTOMNA003)](catalog/devices/fireos-amazon-aftomna003.yaml) | 8.0 | tested | [@zaco-tm](https://github.com/zaco-tm) |
| [Fire TV Stick 4K Max, 2nd gen (AFTKRT)](catalog/devices/fireos-amazon-aftkrt.yaml) | 8.1 | tested | [@gerardo-galvez-hl](https://github.com/gerardo-galvez-hl), [@derkstample](https://github.com/derkstample), [@aboubakrine](https://github.com/aboubakrine), [@s-razaq](https://github.com/s-razaq) |
| [Fire TV Stick 4K Plus (AFTMA08C15)](catalog/devices/fireos-amazon-aftma08c15.yaml) | 8.1 | tested | [@edrmundy](https://github.com/edrmundy) |
| [Fire TV Edition television (AFTOLCBC82)](catalog/devices/fireos-amazon-aftolcbc82.yaml) | 8.1 | tested | [@koconnorgit](https://github.com/koconnorgit), [@iotemylabs](https://github.com/iotemylabs) |
| [Fire TV smart TV (AFTHY7ABBA)](catalog/devices/fireos-amazon-afthy7abba.yaml) | 8.1 | tested | [@bring-nirachornkul](https://github.com/bring-nirachornkul) |
| [Fire TV Stick 4K, 2nd gen (AFTKM), RS8185](catalog/devices/fireos-amazon-aftkm-rs8185.yaml) | 8.1 | tested | [@Rouchekoff](https://github.com/Rouchekoff) |
| [Amazon AFTNA1322D](catalog/devices/fireos-amazon-aftna1322d.yaml) | 8.1 | tested | [@1xretr0](https://github.com/1xretr0) |
| [Fire TV Stick, 3rd gen (AFTSSS)](catalog/devices/fireos-amazon-aftsss.yaml) | 7.7 | partial | [@B3NR1S](https://github.com/B3NR1S) |
| [Fire TV Stick 4K Max, 1st gen (AFTKA)](catalog/devices/fireos-amazon-aftka-ps7697.yaml) | 7.6 | partial | [@dchuk](https://github.com/dchuk) |
| [Fire TV Cube, 2nd gen (AFTR), PS7699](catalog/devices/fireos-amazon-aftr-ps7699.yaml) | 7.6 | partial | [@sant1ago](https://github.com/sant1ago) |
| [Hisense 58U6HF (AFTHA001)](catalog/devices/fireos-hisense-aftha001.yaml) | 7.7 | partial | [@murilaiooo](https://github.com/murilaiooo) |
| [Redmi Smart Fire TV 32 (AFTMV00)](catalog/devices/fireos-redmi-aftmv00.yaml) | 7.7 | partial | [@NeutronZero](https://github.com/NeutronZero) |
| [Fire TV Stick 4K, 2nd gen (AFTKM)](catalog/devices/fireos-amazon-aftkm.yaml) | 8.1 | partial | [@gleesonb](https://github.com/gleesonb), [@humblemuzzu](https://github.com/humblemuzzu) |
| [Insignia 50" F50 (AFTHY7ABBA)](catalog/devices/fireos-insignia-afthy7abba.yaml) | 8.1 | partial | [@dave137-beep](https://github.com/dave137-beep) |
| [Fire TV 4-Series 55" EU (AFTNA67200)](catalog/devices/fireos-amazon-aftna67200.yaml) | 8.1 | partial | [@Chartmann96](https://github.com/Chartmann96) |

Android TV and Google TV reports, run by hand from the lists in
`data/androidtv/`:

| Device | Android | Tested by |
|---|---|---|
| [BenQ GP520 projector](catalog/devices/androidtv-benq-gp01.yaml) | 11 | [@KasperHonore](https://github.com/KasperHonore) |
| [Chromecast with Google TV 4K](catalog/devices/androidtv-google-chromecast-4k.yaml) | 14 | [@KasperHonore](https://github.com/KasperHonore) |
| [Changhong AI PONT](catalog/devices/androidtv-changhong-ai-pont.yaml) | 14 | [@frogu](https://github.com/frogu) |
| [Hisense SmartTV 4K FFM](catalog/devices/androidtv-hisense-smarttv-4k-ffm.yaml) | 11 | [@aswinaasokan18-spec](https://github.com/aswinaasokan18-spec) |
| [Hisense 65U8QG](catalog/devices/androidtv-hisense-65u8qg.yaml) | 14 | [@jlaska](https://github.com/jlaska) |
| [onn. Streaming Device 4K Pro](catalog/devices/androidtv-onn-streaming-device-4k-pro.yaml) | 14 | [@OSikorsky](https://github.com/OSikorsky) |
| [Philips 24NA 4K](catalog/devices/androidtv-philips-24na-4k.yaml) | 14 | [@lsukev](https://github.com/lsukev) |
| [Philips TA6 LT](catalog/devices/androidtv-philips-ta6-lt.yaml) | 14 | [@fabricio00101](https://github.com/fabricio00101) |
| [OnePlus TV Y Series](catalog/devices/androidtv-oneplus-y-series.yaml) | 11 | [@priyank-saarthios-ai](https://github.com/priyank-saarthios-ai) |
| [Philips OLED705, PUS7304, PUS7334 (TPM191E)](catalog/devices/androidtv-philips-tpm191e.yaml) | 12 | [@tkightly](https://github.com/tkightly), [@damsma](https://github.com/damsma), [@PazkaL](https://github.com/PazkaL) |
| [TCL Smart TV Pro G08](catalog/devices/androidtv-tcl-g08.yaml) | 14 | [@maam00](https://github.com/maam00) |
| [Xiaomi Mi TV Stick](catalog/devices/androidtv-xiaomi-mi-tv-stick.yaml) | 9 | [@pfTheTrial](https://github.com/pfTheTrial) |
| [Xiaomi Mi TV (MSSP3)](catalog/devices/androidtv-xiaomi-mitv-mssp3.yaml) | 9 | [@frogu](https://github.com/frogu) |

For a full Android TV and Google TV tool, see
[cleanyourtv](https://github.com/Gathin23/cleanyourtv). Samsung, LG, Roku and other
TVs give owners no way in; [docs/OTHER-TVS.md](docs/OTHER-TVS.md) explains each
one and what you can still do.

**Your TV not here?** Run it, then `./scripts/contribute.sh`. It writes the report
for you. [CONTRIBUTING.md](CONTRIBUTING.md) has the details.

## Undo

```sh
./scripts/restore.sh
```

It re-enables every package, clears the app and network rules, puts back the
settings the TV had before (from the snapshot `preflight.sh` takes), removes the
filter and gives HOME back to Amazon. An app you asked to have uninstalled comes
back from the Appstore.

## Good to know

- **Nothing is written to `/system`**, the part of the TV whose modification
  bricks it. A factory reset removes everything dumbtv did.
- **Fire OS updates are blocked** while the DNS filter runs, since an update would
  switch a lot of this back on. [The FAQ](docs/FAQ.md) explains how to update
  anyway.
- **It cannot replace Fire OS.** The boot chain is locked by Amazon.
  [Here is why](docs/WHY-NOT-REPLACE-OS.md).
- **YouTube ads can't be blocked by DNS.** They come from the same servers as the
  videos. [docs/YOUTUBE.md](docs/YOUTUBE.md) explains, and what to use instead.

## Documentation

| | |
|---|---|
| [dumbtv.org](https://dumbtv.org) | the website: guides, every package, every device |
| [How it works](docs/HOW-IT-WORKS.md) | each part and why it is needed |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | symptoms and fixes |
| [FAQ](docs/FAQ.md) | updates, Prime Video, factory reset, is this legal |
| [Other TVs](docs/OTHER-TVS.md) | Android TV, Samsung, LG, Roku and the rest |
| [YouTube](docs/YOUTUBE.md) | why its ads cannot be blocked, and SmartTube |
| [Why not replace the OS](docs/WHY-NOT-REPLACE-OS.md) | the locked boot chain |
| [dumbtv home](launcher/README.md) | the launcher |
| [Device catalog](catalog/README.md) | per-model notes |
| [AGENTS.md](AGENTS.md) | instructions for coding agents |
| [Press](docs/PRESS.md) | coverage |

## Thanks

To everyone who tested a TV, wrote code, or reported a problem. dumbtv works on all
the devices above because of you.

<a href="https://github.com/humblemuzzu/dumbtv/graphs/contributors"><img src="https://contrib.rocks/image?repo=humblemuzzu/dumbtv" alt="Contributors"></a>

**Code, fixes and device reports:**
[@fabricio00101](https://github.com/fabricio00101),
[@MrVoicer](https://github.com/MrVoicer),
[@Jovci](https://github.com/Jovci),
[@niz-coder](https://github.com/niz-coder),
[@centsandcode](https://github.com/centsandcode),
[@maam00](https://github.com/maam00),
[@alfibi](https://github.com/alfibi),
[@theChef613](https://github.com/theChef613),
[@rachit-jain](https://github.com/rachit-jain),
[@daviavmello](https://github.com/daviavmello),
[@yuriwc](https://github.com/yuriwc),
[@lsukev](https://github.com/lsukev),
[@dchuk](https://github.com/dchuk),
[@gerardo-galvez-hl](https://github.com/gerardo-galvez-hl),
[@koconnorgit](https://github.com/koconnorgit),
[@pfTheTrial](https://github.com/pfTheTrial),
[@frogu](https://github.com/frogu),
[@podulator](https://github.com/podulator),
[@tkightly](https://github.com/tkightly),
[@OSikorsky](https://github.com/OSikorsky),
[@nolanvargas](https://github.com/nolanvargas),
[@harupong](https://github.com/harupong),
[@gtalusan](https://github.com/gtalusan),
[@B3NR1S](https://github.com/B3NR1S),
[@bglaszcz](https://github.com/bglaszcz),
[@murilaiooo](https://github.com/murilaiooo),
[@dnpkuk](https://github.com/dnpkuk),
[@timcunningham-sv](https://github.com/timcunningham-sv),
[@iotemylabs](https://github.com/iotemylabs),
[@aswinaasokan18-spec](https://github.com/aswinaasokan18-spec),
[@edrmundy](https://github.com/edrmundy),
[@MarioMartReq](https://github.com/MarioMartReq),
[@ravishan16](https://github.com/ravishan16),
[@jonathanrzarzuela-cloud](https://github.com/jonathanrzarzuela-cloud),
[@Loadedrakeman](https://github.com/Loadedrakeman),
[@ryanlucht](https://github.com/ryanlucht),
[@tharun634](https://github.com/tharun634),
[@NimorM](https://github.com/NimorM),
[@RabeJonas](https://github.com/RabeJonas),
[@Prathamesh913](https://github.com/Prathamesh913),
[@zaco-tm](https://github.com/zaco-tm),
[@NeutronZero](https://github.com/NeutronZero),
[@adi-c](https://github.com/adi-c),
[@derkstample](https://github.com/derkstample),
[@KasperHonore](https://github.com/KasperHonore),
[@damsma](https://github.com/damsma),
[@arielvieira](https://github.com/arielvieira),
[@stedrow](https://github.com/stedrow),
[@zekesax](https://github.com/zekesax)

**Bug reports:**
[@gleesonb](https://github.com/gleesonb),
[@dennus280-ship-it](https://github.com/dennus280-ship-it),
[@FyllyMan](https://github.com/FyllyMan),
[@nairyash](https://github.com/nairyash),
[@PWannop](https://github.com/PWannop),
[@jt-at-home](https://github.com/jt-at-home),
[@thearchivist9778-ai](https://github.com/thearchivist9778-ai),
[@GrackleCrow](https://github.com/GrackleCrow),
[@MisterKOak](https://github.com/MisterKOak)

And to the projects dumbtv stands on:
[personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter) by Ingo Zenz,
[HaGeZi's DNS blocklists](https://github.com/hagezi/dns-blocklists),
[SmartTube](https://github.com/yuliskov/SmartTube) by Yuriy Liskov, and
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) by
[@Gathin23](https://github.com/Gathin23) for Android TV and Google TV.

## Legal

dumbtv changes settings on a device you own, through the developer tools the
manufacturer ships. It does not bypass DRM, unlock anything or touch anyone's
account. Fire TV, Fire OS, Alexa and Prime Video are trademarks of Amazon; dumbtv is
not affiliated with or endorsed by Amazon. Use it at your own risk.

## Licence

Scripts, docs and the launcher are [MIT](LICENSE). The device catalog is
[CC BY-SA 4.0](catalog/LICENSE).

dumbtv bundles none of these, but downloads them when you ask for them:
personalDNSfilter (GPL-2.0) from F-Droid, HaGeZi's Amazon tracker list (GPL-3.0),
and SmartTube (MIT) from its own releases.
