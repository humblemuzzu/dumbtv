#!/usr/bin/env python3
"""A TV showing one app screen, for tests/test_appui.py.

TAB moves focus round VIEWS in order, OK ticks or unticks the focused checkbox, and taps are
ignored the way a phone-sized app window on a TV ignores them. The first FAILED_DUMPS
dumps fail, as they do while an app is busy. `dumpsys backup` answers BACKUP_FIRST_LINE.

`dumpsys activity top` lists the views too, unless DIALOG=1 puts them in a dialog, which
that dump leaves out. STEAL_TO names the view personalDNSfilter moves the focus to when it
logs a line: a uiautomator dump rebinds dumbtv home's Guard, which wakes the filter, so the
next key after a dump starts from that view, until advancedConfigCheck is ticked."""
import json
import os
import sys
from pathlib import Path

d = Path(os.environ['FAKE_DIR'])
state_file = d / 'state.json'
views = os.environ.get('VIEWS', '').split()
state = json.loads(state_file.read_text()) if state_file.exists() else {
    'focus': 0, 'checked': os.environ.get('CHECKED', '').split(), 'dumps': 0, 'steal': False}
cmd = ' '.join(sys.argv[4:])
with (d / 'log').open('a') as f:
    f.write(cmd + '\n')

if cmd == 'dumpsys backup':
    print(os.environ['BACKUP_FIRST_LINE'])
    print('Last backup pass started: 0')
elif cmd == 'dumpsys activity top':
    print('  View Hierarchy:')
    if os.environ.get('DIALOG') != '1':
        for i, v in enumerate(views):
            focused = 'F' if i == state['focus'] else '.'
            print(f'      android.widget.CheckBox{{4eab59{i} VFED..C.. .{focused}...... 30,4-399,87 '
                  f'#7f05000{i} app:id/{v}}}')
elif cmd.startswith('uiautomator dump'):
    state['dumps'] += 1
    state['steal'] = bool(os.environ.get('STEAL_TO')) and 'advancedConfigCheck' not in state['checked']
    if state['dumps'] <= int(os.environ.get('FAILED_DUMPS', '0')):
        print('cat: /sdcard/dumbtv-ui.xml: No such file or directory')
        state_file.write_text(json.dumps(state))
        sys.exit(0)
    print('<?xml version="1.0"?><hierarchy rotation="0">', end='')
    for i, v in enumerate(views):
        print(f'<node index="{i}" text="" resource-id="app:id/{v}" '
              f'checked="{str(v in state["checked"]).lower()}" '
              f'focused="{str(i == state["focus"]).lower()}" bounds="[0,0][10,10]" />', end='')
    print('</hierarchy>')
elif cmd in ('input keyevent KEYCODE_TAB', 'input keyevent KEYCODE_DPAD_CENTER'):
    if state['steal']:
        state['focus'] = views.index(os.environ['STEAL_TO'])
        state['steal'] = False
    if cmd.endswith('TAB'):
        state['focus'] = (state['focus'] + 1) % len(views)
    else:
        v = views[state['focus']]
        state['checked'] = [c for c in state['checked'] if c != v] if v in state['checked'] else state['checked'] + [v]
state_file.write_text(json.dumps(state))
