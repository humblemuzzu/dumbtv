# AGENTS.md

Instructions for a coding agent (Claude Code, Codex, Cursor, Gemini, or any
other) running dumbtv on someone's TV. Read all of it before the first command.

You are changing a television a household uses every day. The scripts are built so
that an agent following this file never has to improvise. When something here does
not cover what you see, stop and tell the owner rather than guess.

## What dumbtv does

On a Fire TV, over ADB, with no root:

- **Always:** disables Amazon's ad, sponsored-content, screen-fingerprinting (ACR),
  telemetry and experiment packages; turns off ad tracking and usage reporting.
  Amazon packages are disabled, never uninstalled.
- **If the owner agrees:** an on-TV DNS filter that blocks Amazon's telemetry and
  update servers, and dumbtv home in place of Amazon's home screen.
- **Only if the owner names them:** removing apps, switching off Alexa, replacing
  YouTube with SmartTube.

Every step is undone by `./scripts/restore.sh`.

## Hard rules

1. **Never write to `/system`.** No `adb remount`, no `adb root`, no hosts-file
   edits, no deleting system APKs. dm-verity checks `/system`, and a write bricks the
   TV.
2. **Never factory-reset the TV.**
3. **Never disable anything in a `never-disable.txt`** (`data/fireos/`,
   `data/fireos6/`, `data/androidtv/`). They hold the launcher, settings, inputs,
   tuner, remote pairing and system UI. The scripts enforce this; do not work around
   them.
4. **Never remove an app the owner did not name.** Streaming apps, games, YouTube,
   anything they installed: it stays unless they asked for it to go, by name. If you
   are not sure which package a name means, look it up in `data/apps.txt` or ask.
5. **Never invent package names.** Every package you act on must appear in
   `adb shell pm list packages` on that TV.
6. **Ask before the first change** unless the owner already said to go ahead.
7. **YouTube is off by default.** Ask once, as a question (see the interview). If
   they say yes, `youtube.sh` disables the stock app; it only deletes it with
   `--remove`, and only when they asked for it gone in those words. Their Google
   account and subscriptions are never touched.
8. **Never `am force-stop dnsfilter.android`.** It leaves the filter running but
   filtering nothing until the TV reboots.
9. **Never disable `com.amazon.vizzini`.** It keeps running anyway, and on some
   TVs it covers the screen with "Unable to Detect Your Remote".
10. **One TV at a time.** If `adb devices` lists several, set `ADB_SERIAL`.
11. **Keep identifiers out of everything you publish.** IP addresses, MAC addresses,
    serial numbers, Wi-Fi names and account emails never go into a commit, an
    issue, a pull request or a screenshot. `report/` is git-ignored for this reason.

## Which TVs

| Platform | What to do |
|---|---|
| Fire OS 6, 7, 8 (`ro.product.manufacturer` starts with `Amazon`) | the scripts below |
| Fire OS 5 and older | nothing: they refuse `pm disable-user`. Say so. |
| Android TV, Google TV | by hand, from `data/androidtv/packages.txt` and its `never-disable.txt`; read [docs/OTHER-TVS.md](docs/OTHER-TVS.md) first. The scripts refuse these. |
| Samsung, LG, Roku, Vizio, Hisense VIDAA, Panasonic, Philips Saphi | no shell for an owner. Say so plainly, point at [docs/OTHER-TVS.md](docs/OTHER-TVS.md), and do not invent steps. |

Check [catalog/devices/](catalog/devices/) for the model first: the menu path,
where the IP is shown and the dialogs that need a person are recorded there. If the
model has no entry, say so instead of guessing a menu path.

## Connect

The owner turns on developer mode and ADB debugging (Fire OS: Settings → My Fire TV
→ About → press OK on the device name 7 times, then Developer Options → ADB
debugging). Then:

```sh
adb connect <tv-ip>:5555
adb devices                                  # the line must end in "device"
adb shell getprop ro.product.manufacturer    # Amazon = Fire OS
adb shell getprop ro.build.version.sdk       # 25 Fire OS 6, 28 Fire OS 7, 30 Fire OS 8
```

`unauthorized` means the TV shows "Allow USB debugging?". A person has to press
Allow (and tick "Always allow"). Tell them, and do not retry in a loop.

## Step 1: interview the owner

Ask these in plain words and wait for the answers. Read the defaults out; most
people take them.

| # | Ask | Default | Flag |
|---|---|---|---|
| 1 | "Do you watch Prime Video?" No blocks the servers it needs. | yes | `--prime 0\|1` |
| 2 | "Do you use Alexa or the voice button on the remote?" No switches off Alexa and the app that owns the microphone. | keep | `--alexa 0\|1` |
| 3 | "Replace Amazon's home screen with dumbtv home?" Open source, apps and inputs only, no ads, no internet access. | yes | `--launcher 0\|1` |
| 4 | "Run the DNS filter on the TV?" It is what stops telemetry and automatic updates. Without it, the protected Amazon packages keep reporting. | yes | `--dnsfilter 0\|1` |
| 5 | "Are there apps you want removed?" Get names, map them to packages with `data/apps.txt` and `adb shell pm list packages -3`, and read the list back before recording it. | none | `--remove "pkg pkg"` |
| 6 | "Do you want the YouTube app replaced with SmartTube?" No ads and no Shorts, a different app, same Google account. Ask once; if no, drop it. | no | `--youtube 0\|1` |

`--keep "pkg pkg"` keeps a package that would otherwise be disabled, for an owner
who wants a specific Amazon feature. Fire OS 6 has one more question
(`--system-dns`); its default is right for almost everyone. `--dns-upstream` is
not one of the questions: leave it unset unless the owner names resolver IPs.
Empty means the filter forwards unknown names to the router.

Record the answers:

```sh
./scripts/configure.sh                     # asks the same questions on the terminal
./scripts/configure.sh -y --alexa 0 --remove "com.example.app"   # or with flags; -y takes defaults for the rest
```

That writes `dumbtv.conf` (git-ignored), which every other script reads. If the
owner changes their mind, run `configure.sh` again. Do not edit the scripts for
preferences.

## Step 2: run it

```sh
./scripts/preflight.sh     # read-only: what the TV is and what would change
./scripts/all.sh           # asks before each change, reboots twice, about 5 minutes
./scripts/verify.sh        # must exit 0
```

Step by step instead, in this order:

1. `./scripts/debloat.sh -y`, then reboot and check the TV reaches a home screen.
2. `./scripts/lockdown.sh -y`
3. `./scripts/dnsfilter.sh -y` (reboots and tests itself)
4. `./scripts/launcher.sh -y`
5. `./scripts/youtube.sh -y`, only if `YOUTUBE=1`
6. `./scripts/verify.sh`

Every script that changes the TV takes `--dry-run`, which prints what it would do
and changes nothing.

## Reading the output

`debloat.sh` prints one line per package:

| Word | Meaning |
|---|---|
| `disabled` | done |
| `already disabled by the system` | the TV had it off already |
| `absent` | not on this TV; normal, the lists cover many models |
| `PROTECTED` | Amazon refuses to let it be disabled. Expected; `lockdown.sh` and the filter deal with it |
| `still running -> ops off, data blocked` | disabled but kept alive by the system, so its background running and data are cut |
| `uninstalled` | an app the owner named, removed |
| `SKIP (keep list)` | the owner kept it |
| `SKIP (never-disable list)` | the TV needs it |
| `SUSPECT` | failed for another reason. Stop and report it; do not add packages to work around it |

`verify.sh` prints a line per check and ends with `all checks passed`:

```
ok         softwareupdates.amazon.com   blocked
ok         www.youtube.com              resolves
ok home bound, front is tv.dumbtv.home/.Home
packages 51 disabled
ok filter 416 entries
ok vpn dnsfilter.android
all checks passed
```

A blocked host resolving to a public address means the filter is not in the DNS
path. Use the table below; do not disable more packages.

## When something is wrong

| What the owner sees | Fix |
|---|---|
| Amazon's home screen is back | `./scripts/launcher.sh -y` |
| Amazon's home shows for ~5 s before dumbtv home | `./scripts/launcher.sh -y` (an update reset the alarm setting) |
| Nothing is blocked, but the filter is running | reboot the TV |
| "Unable to Detect Your Remote" over everything | `adb shell pm enable --user 0 com.amazon.vizzini`, then reboot |
| An app stopped working | find the host it needs and remove it from `data/blocklists/extra-hosts.txt`, then `./scripts/dnsfilter.sh -y` |
| They want an app back | `adb shell pm enable --user 0 <package>`, or the Appstore if it was uninstalled |
| Start over | `./scripts/restore.sh -y` |

[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) has the rest, sorted by
symptom.

## A TV the lists do not cover

1. `./scripts/discover.sh` lists the packages and flags names that look like ads or
   analytics. It changes nothing.
2. Check each candidate on that TV: disable it, reboot, and confirm the home screen,
   inputs, remote and apps still work.
3. Run `debloat.sh --dry-run`, then for real. Add each `PROTECTED` to the matching
   `protected.txt`.
4. Never add a package that is not on the device, and never take one out of a
   `never-disable.txt`.

## After a session: contribute

When `verify.sh` exits 0, or when you hit something new, offer to record it:

```sh
./scripts/contribute.sh
```

It reads the model from the TV and asks how developer mode was enabled, where the
IP is shown, which dialogs needed a person, what was surprising, and the owner's
GitHub username for credit. It writes `catalog/devices/<platform>-<brand>-<model>.yaml`
and prints the pull-request commands.

- `tested` means the full pass ran and `verify.sh` exited 0 on that TV.
- One device per pull request.
- Summarise results with the verify table and the counts. Never paste `report/`.

## Working on the code

[CONTRIBUTING.md](CONTRIBUTING.md) has the checks to run before a pull request. In
short: `python3 -m unittest discover -s tests`, `python3 catalog/validate.py`, and
`bash -n` on every script. The tests run the scripts against a pretend TV
(`tests/fake_tv.py`) and never touch a real one.
