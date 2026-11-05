# Contributing

Thanks for helping more TVs lose their ads. Three kinds of contribution are the
most useful.

Working with a coding agent? Point it at [AGENTS.md](AGENTS.md): the same rules,
written for agents.

## 1. Report your TV

The most valuable thing you can send is what happened on your TV: the menu path,
the dialog that blocked you, the package that refused to disable. The next person
with that model skips all of it.

**From the TV**, at the end of a session:

```sh
./scripts/contribute.sh
```

It reads the brand, model, Android version and build from the TV and asks for the
rest:

- how you turned on developer mode and ADB
- where the TV shows its IP address
- each dialog that blocked automation, and what dismissed it (`name|fix`)
- anything that surprised you
- your GitHub username, so the report credits you

It writes `catalog/devices/<platform>-<brand>-<model>.yaml`, validates it and prints
the `git` and `gh` commands for the pull request. Once merged, the report appears
on [dumbtv.org/tv](https://dumbtv.org/tv) within a day. For unattended runs every answer
has a flag: `--brand`, `--model`, `--platform`, `--status`, `--developer-mode`,
`--ip-location`, `--prompt "name|fix"`, `--quirk "text"`, `--models "A,B"`,
`--notes "text"`, `--github handle`, `--no-device`, `--dry-run`.

**From a browser**, with no git: open an issue with the
[Device report](https://github.com/humblemuzzu/dumbtv/issues/new?template=device-report.yml)
template.

What makes a good report:

- **An honest status.** `tested` means the full pass ran and `verify.sh` exited 0 on
  that device; otherwise `partial` or `untested`.
- **Blocking dialogs with their fix.** "A dialog appeared" helps nobody; "Allow
  USB debugging | tick Always allow, press OK on the remote" does.
- **Quirks that would confuse a newcomer:** a blank screen during boot, a package
  that keeps running after it is disabled, a menu in a different place.
- **One device per pull request.** If your TV needs different package lists, say so
  in the report and send the lists in a second pull request.

## 2. Fix something

Scripts are bash, written to run on the bash 3.2 that ships with macOS. The
launcher is plain Java with no libraries; [launcher/README.md](launcher/README.md)
explains how it is built.

The rules the code keeps, and that a change must keep too:

- Nothing writes to `/system`, and nothing on a `never-disable.txt` list is ever
  touched.
- Amazon packages are disabled, never uninstalled.
- No app a person uses is removed unless they named it.
- On Fire OS 7 and 8 the launcher asks for one permission, run at startup (it
  restarts the DNS filter), and has no internet access. CI enforces this.
- Every step can be undone with `scripts/restore.sh`.

Test on a real TV, say which one and which build in the pull request, and run the
checks below.

## 3. Add to the lists

`data/fireos/packages.txt` is for Amazon packages that only serve ads, tracking,
telemetry, experiments or update nags. Before adding one, check on your TV that the
home screen, inputs, the remote and your apps still work after a reboot. Say what
the package does in a comment on the same line. Apps people use go in
`user-apps.txt`, which is only ever removed on request.

Blocked hosts go in `data/blocklists/extra-hosts.txt`. A host that an app needs to
work does not belong there.

## Checks

CI runs these on every pull request. Run them before you push:

```sh
python3 catalog/validate.py                 # catalog files
python3 -m unittest discover -s tests -v    # scripts, against a pretend TV
for f in scripts/*.sh launcher/*.sh; do bash -n "$f"; done
./launcher/release-check.sh --manifest
```

The tests never touch a real TV: `tests/fake_tv.py` answers adb commands from a
small state file and records every command a script sends, so a test can check
exactly what a run would change.

## What never goes in

IP addresses, MAC addresses, serial numbers, Wi-Fi names, account emails, or
screenshots that show any of them. `contribute.sh` asks you to remove anything that
looks like an IP or MAC, and CI fails a pull request that contains a private
address.

## Licence

Code is MIT ([LICENSE](LICENSE)). The catalog in `catalog/` is CC BY-SA 4.0
([catalog/LICENSE](catalog/LICENSE)). By sending a contribution you agree it can be
published under those terms.
