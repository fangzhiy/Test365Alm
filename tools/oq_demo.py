"""Explicit SYNTHETIC OQ-DEMO-01 inspect/resume on the separate persistent demo.

Startup/stop remain demo_up/demo_down --profile oq-demo-01. No business writes
occur in inspect or guard mode. Resume uses real OIDC APIs and browser signing.
"""
import argparse
import contextlib
import json
from pathlib import Path
import subprocess
import sys

import demo_up
from demo_seed import Api, TENANT, DOMAIN


def main():
    if '--profile' not in sys.argv:
        raise RuntimeError('Explicit --profile oq-demo-01 required; legacy demo is never selected')
    demo_up.select_profile_args()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['guard', 'inspect', 'resume'], default='inspect')
    args = parser.parse_args()
    with (contextlib.nullcontext() if args.mode == 'guard' else demo_up.operation_lock()):
        config = demo_up.load_env()
        demo_up.validate_config(config)
        processes = demo_up.validate_state(config)
        if not all(demo_up.process_owned(processes.get(r, 0), config, r) for r in ('web', 'server')):
            raise RuntimeError('Start this owned profile first; no other service will be used')
        if args.mode == 'guard':
            print(json.dumps({'project': demo_up.PROJECT, 'origin': config['TEST365ALM_WEB_ORIGIN'], 'owned': True}))
            return 0
        checkpoint = demo_up.LOCAL / 'scenario.json'
        if args.mode == 'inspect':
            if not checkpoint.exists():
                print('SYNTHETIC OQ-DEMO-01 not created; use --mode resume explicitly')
                return 0
            saved = json.loads(checkpoint.read_text(encoding='utf-8'))
            api = Api(config['TEST365ALM_WEB_ORIGIN'], config['TEST365ALM_OIDC_ISSUER'])
            api.login(config['R03_TEST_USER'], config['R03_TEST_USER_PASSWORD'])
            result = inspect(api, saved)
            (demo_up.LOCAL / 'inspection.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(result, ensure_ascii=False))
            return 0
        ids = []
        for u, p in [('R03_TEST_USER', 'R03_TEST_USER_PASSWORD'), ('R03_MEMBER_USER', 'R03_MEMBER_PASSWORD'), ('R03_VIEWER_USER', 'R03_VIEWER_PASSWORD')]:
            api = Api(config['TEST365ALM_WEB_ORIGIN'], config['TEST365ALM_OIDC_ISSUER'])
            ids.append(api.login(config[u], config[p]))
        demo_up.bootstrap(config, ids)  # Only the authorized tenant boundary, never business records.
        env = demo_up.child_env(config)
        env.update(OQ_DEMO_ORIGIN=config['TEST365ALM_WEB_ORIGIN'], OQ_DEMO_CHECKPOINT=str(checkpoint),
                   OQ_DEMO_TENANT=TENANT, OQ_DEMO_DOMAIN=DOMAIN,
                   R03_LOCAL_RESULTS_PATH=str(demo_up.LOCAL / 'browser-results.json'))
        if Path('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe').is_file():
            env['R03_E2E_BROWSER_CHANNEL'] = 'msedge'
        # No GITHUB_* identity, resource guard override, browser trace or token output.
        command = ['node', 'node_modules/@playwright/test/cli.js', 'test', 'e2e/oq-demo.spec.ts',
                   '--config', 'playwright.demo.config.ts', '--workers=1', '--retries=0']
        with (demo_up.LOCAL / 'browser.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(command, cwd=demo_up.ROOT / 'apps/web', env=env, stdout=log, stderr=subprocess.STDOUT)
        print('SYNTHETIC browser exit:', result.returncode, '; evidence:', demo_up.LOCAL)
        return result.returncode


def inspect(api, saved):
    if saved.get('scenario') != 'OQ-DEMO-01' or saved.get('version') != '1.1':
        raise RuntimeError('Unknown scenario checkpoint; preserved without writes')
    root = '/api/v1/projects/' + saved['projectId']
    project = api.call(root)
    if project['code'] != 'SYNTHETIC-OQ-DEMO-01-V1-1':
        raise RuntimeError('Checkpoint project ownership mismatch')
    rows = []
    for identity, item in saved['cases'].items():
        revision = api.call(root + '/tests/' + item['testId'] + '/revisions/' + item['revisionId'])
        if not revision['title'].startswith('SYNTHETIC ' + identity + ' '):
            raise RuntimeError('Scenario revision identity mismatch; no overwrite')
        current = api.call(root + '/runs/' + item['runId']) if item.get('runId') else None
        rows.append({'case': identity, 'revision': item['revisionId'], 'currentAttempt': current['currentAttempt']['id'] if current else None,
                     'status': current['currentAttempt']['status'] if current else 'NOT_RUN',
                     'conclusion': current['currentAttempt']['conclusion'] if current else None})
    return {'scenario': saved['scenario'], 'version': saved['version'], 'classification': 'SYNTHETIC',
            'projectId': saved['projectId'], 'completedStages': saved.get('stages', []), 'readOnly': True, 'actual': rows}


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
