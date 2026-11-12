"""Stop only manifest-verified demo resources; volumes survive unless --delete-data is explicit."""
import argparse
import os
import signal
import sys

import demo_up
if __name__ == '__main__':
    demo_up.select_profile_args()

from demo_up import (ENV, STATE, entrypoint, inventory, load_env, process_owned, run, save_state, validate_state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--delete-data', action='store_true', help='Explicitly delete only demo-owned containers, networks and data volumes')
    args = parser.parse_args()
    try:
        if not ENV.exists() or not STATE.exists():
            raise RuntimeError('Demo config/ownership manifest missing; no resources touched')
        config = load_env()
        processes = validate_state(config)
        resources = inventory(config)
        # Verify every process before the first mutation; an old PID must never kill an unrelated app.
        live = []
        for role, pid in processes.items():
            if process_owned(pid, config, role):
                live.append(pid)
        for pid in live:
            if os.name == 'nt':
                run(['taskkill.exe', '/PID', str(pid), '/T', '/F'], capture=True)
            else:
                os.killpg(pid, signal.SIGTERM)
        ids = [c['Id'] for c in resources['containers']]
        if ids:
            run(['docker', 'stop', *ids], capture=True)
        if args.delete_data:
            inventory(config)
            if ids:
                run(['docker', 'rm', *ids], capture=True)
            for network in resources['networks']:
                run(['docker', 'network', 'rm', network['Id']], capture=True)
            for volume in resources['volumes']:
                run(['docker', 'volume', 'rm', volume['Name']], capture=True)
        save_state(config, {})
        print('Stopped ' + demo_up.PROJECT + '; ' + ('demo data volumes deleted explicitly' if args.delete_data else 'data volumes retained'))
        return 0
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(entrypoint(main))
