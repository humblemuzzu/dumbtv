#!/usr/bin/env python3
"""A pretend Fire TV for the scripts: answers adb commands from a JSON state file
and logs every shell command, so tests can see exactly what a script would do.

Like a real TV on Wi-Fi, stopping the DNS filter's VPN breaks the adb session
until the host disconnects and connects again."""
import json
import os
import sys
from pathlib import Path

VPN_APP = 'dnsfilter.android'

args = sys.argv[1:]
state_path = Path(os.environ['FAKE_TV_STATE'])
state = json.loads(state_path.read_text())


def save():
    state_path.write_text(json.dumps(state))


if args == ['start-server'] or args[:1] == ['connect']:
    sys.exit(0)
if args[:1] == ['disconnect']:
    state['dropped'] = False
    save()
    sys.exit(0)
if args[-1:] == ['get-state']:
    print('device')
    sys.exit(0)
if state.get('crlf'):
    # Some hosts' adb ends every line of shell output in CR LF.
    sys.stdout.reconfigure(newline='\r\n')
if len(args) == 5 and args[0] == '-s' and args[2] == 'install' and 'installs' in state:
    # Installs the package the test named for this APK, at its versionCode.
    name, code = state['installs']['package'], state['installs']['versionCode']
    state['packages'][name] = {'system': False, 'enabled': True}
    state.setdefault('versions', {})[name] = code
    save()
    print('Performing Streamed Install')
    print('Success')
    sys.exit(0)
if len(args) == 5 and args[0] == '-s' and args[2] == 'push' and 'pushed' in state:
    # Keeps a copy of each pushed file, so a test can read what reached the TV.
    Path(state['pushed']).mkdir(parents=True, exist_ok=True)
    Path(state['pushed'], Path(args[4]).name).write_bytes(Path(args[3]).read_bytes())
    print(f'{args[3]}: 1 file pushed')
    sys.exit(0)
if len(args) < 3 or args[0] != '-s' or args[2] != 'shell':
    sys.exit(91)

command = ' '.join(args[3:])
if state.get('dropped'):
    print('error: closed')
    sys.exit(1)
with Path(os.environ['FAKE_TV_LOG']).open('a') as log:
    log.write(command + '\n')

apps = state['packages']
settings = state.setdefault('settings', {})
words = command.split()

if words[:1] == ['settings']:
    verb, rest = words[1], words[2:]
    if verb == 'put' and len(rest) == 3:
        settings[f'{rest[0]}/{rest[1]}'] = '' if rest[2] == '""' else rest[2]
        save()
    elif verb == 'put':
        state.setdefault('bad', []).append(command)
        save()
        print('Bad arguments')
    elif verb == 'get' and len(rest) == 2:
        print(settings.get(f'{rest[0]}/{rest[1]}', 'null'))
    elif verb == 'delete' and len(rest) == 2:
        settings.pop(f'{rest[0]}/{rest[1]}', None)
        save()
elif words[:1] == ['device_config']:
    verb, rest = (words[1], words[2:]) if len(words) > 1 else ('', [])
    key = '/'.join(['config'] + rest[:2])
    if verb == 'get' and len(rest) == 2:
        print(settings.get(key, 'null'))
    elif verb in ('put', 'delete') and len(rest) == (3 if verb == 'put' else 2):
        if state.get('device_config_refused'):
            print('Exception occurred while executing: Permission denial')
            sys.exit(255)
        if verb == 'put':
            settings[key] = rest[2]
        else:
            settings.pop(key, None)
        save()
    else:
        state.setdefault('bad', []).append(command)
        save()
        print('Bad arguments')
elif command == 'echo ok':
    print('ok')
elif command == 'cat /proc/sys/kernel/random/boot_id':
    print(f"boot-{state.get('boots', 0)}")
elif command == 'svc power reboot' and state.get('svc_refused'):
    print('Killed')
elif command == 'getprop sys.shutdown.requested':
    print(state.pop('shutting_down', ''))
    save()
elif command in ('svc power reboot', 'reboot'):
    if command == 'svc power reboot':
        state['shutting_down'] = '1reboot'
        save()
    if not state.get('ignores_reboot'):
        state['boots'] = state.get('boots', 0) + 1
        save()
elif command == 'getprop ro.build.version.sdk':
    print(state.get('sdk', 28))
elif command == 'getprop ro.product.manufacturer':
    print('Amazon')
elif command == 'getprop sys.boot_completed':
    print(1)
elif words[:1] == ['getprop'] and len(words) == 2 and words[1] in state.get('props', {}):
    print(state['props'][words[1]])
elif command == 'ip route show table all' and 'routes' in state:
    print(state['routes'])
elif command == 'cmd package help':
    print('Package manager (package) commands:\n  path [--user USER_ID] PACKAGE')
elif words[:2] == ['pm', 'path']:
    if words[2] in apps:
        print(f'package:/system/app/{words[2]}.apk')
elif command == 'pm list packages -s':
    print('\n'.join(f'package:{p}' for p, a in apps.items() if a['system']))
elif command == 'pm list packages -d':
    print('\n'.join(f'package:{p}' for p, a in apps.items() if not a['enabled']))
elif words[:4] == ['pm', 'disable-user', '--user', '0']:
    name = words[4]
    if apps.get(name, {}).get('protected'):
        print('Security exception: Cannot disable a protected package')
    else:
        apps[name]['enabled'] = False
        if name == VPN_APP:
            state['dropped'] = True
        save()
        print(f'Package {name} new state: disabled-user')
elif words[:4] == ['pm', 'enable', '--user', '0']:
    if apps.get(words[4], {}).get('system_disabled'):
        # Shipped disabled by the system (state 2), which the shell may not change.
        print(f'Security exception: Shell cannot change component state for {words[4]}/null to 1')
    elif words[4] in apps:
        apps[words[4]]['enabled'] = True
        save()
        print(f'Package {words[4]} new state: enabled')
elif words[:4] == ['pm', 'uninstall', '--user', '0']:
    apps.pop(words[-1], None)
    save()
    print('Success')
elif words[:2] == ['dumpsys', 'package']:
    print('    userId=10050')
    if words[2:] and words[2] in state.get('versions', {}):
        print(f"    versionCode={state['versions'][words[2]]} minSdk=21 targetSdk=29")
elif words[:1] == ['cat'] and len(words) == 2 and words[1] in state.get('files', {}):
    sys.stdout.write(state['files'][words[1]])
elif words[:1] == ['cat'] and len(words) == 2:
    print(f'cat: {words[1]}: No such file or directory', file=sys.stderr)
    sys.exit(1)
elif words[:2] == ['monkey', '-p']:
    print('Events injected: 1')
elif command == 'ps -A -o NAME':
    print('NAME')
elif command == 'dumpsys activity activities':
    print('  mResumedActivity: ActivityRecord{1 u0 tv.dumbtv.home/.Home t1}')
