#!/usr/bin/env python3
"""Deterministic ADB substitute; unexpected commands fail, never reach hardware."""
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
log_path = Path(os.environ['FAKE_ADB_LOG'])
with log_path.open('a') as log:
    log.write(json.dumps(args) + '\n')
if args == ['start-server']:
    sys.exit(0)
if args[:3] != ['-s', 'test-tv', 'shell']:
    sys.exit(91)
responses = json.loads(Path(os.environ['FAKE_ADB_RESPONSES']).read_text())
command = ' '.join(args[3:])
if command not in responses:
    print('unexpected command: ' + command, file=sys.stderr)
    sys.exit(92)
result = responses[command]
if isinstance(result, list):
    # A queue: every call takes the next entry, the last one repeats. The log says
    # how often the command was asked before, so the answers can follow the run.
    seen = sum(1 for line in log_path.read_text().splitlines()
               if line and json.loads(line)[3:] == args[3:])
    result = result[min(seen - 1, len(result) - 1)]
print(result['out'])
sys.exit(result.get('code', 0))
