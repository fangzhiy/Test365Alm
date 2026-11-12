"""Start the isolated, persistent local demo (Python standard library only)."""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import prepare_r03_dev
from demo_seed import DOMAIN, TENANT, seed
from verify_r11_services import check as assets_ready

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / 'local-evidence' / 'demo'
ENV = ROOT / '.env.demo'
REALM = LOCAL / 'realm.json'
COMPOSE = LOCAL / 'compose.json'
STATE = LOCAL / 'state.json'
PROJECT = 'test365alm-demo'
SERVICES = ('postgres', 'keycloak', 'asset-store', 'asset-scanner')
JAVA_HOME = Path('D:/code/ProOrca/Wetest365ALM/.tools/jdk-21')



def select_profile_args():
    """Select a separate owned environment before locks, secrets or resources are read."""
    global LOCAL, ENV, REALM, COMPOSE, STATE, PROJECT
    if '--profile' not in sys.argv:
        return
    index = sys.argv.index('--profile')
    if index + 1 >= len(sys.argv) or sys.argv[index + 1] != 'oq-demo-01':
        raise RuntimeError('Supported demo profile: oq-demo-01; legacy is the default')
    del sys.argv[index:index + 2]
    LOCAL = ROOT / 'local-evidence/demo-oq-demo-01'
    ENV = ROOT / '.env.demo-oq-demo-01'
    REALM, COMPOSE, STATE = (LOCAL / name for name in ('realm.json', 'compose.json', 'state.json'))
    PROJECT = 'test365alm-demo-oq-demo-01'


def run(args, *, env=None, cwd=ROOT, data=None, capture=False):
    result = subprocess.run([str(a) for a in args], cwd=cwd, env=env, input=data,
                            text=True, encoding='utf-8', errors='replace', capture_output=True)
    if result.returncode:
        # Docker/psql errors can quote interpolated credentials. Keep diagnostics deliberately bounded.
        raise RuntimeError(f'{Path(str(args[0])).name} {args[1] if len(args)>1 else ""} failed (exit {result.returncode}); no secret-bearing output displayed')
    if not capture and result.stdout.strip():
        print(result.stdout.strip())
    return result.stdout.strip()


def load_env(path=None):
    path = path or ENV
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        values[key] = value
    return values


def child_env(config=None):
    # Do not inherit datasource, Spring, JVM, Compose or libpq redirection from a developer shell.
    allowed = ('PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
               'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMFILES', 'PROGRAMFILES(X86)',
               'HOME', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY')
    env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    spring_keys = {'SPRING_FLYWAY_URL', 'SPRING_FLYWAY_USER', 'SPRING_FLYWAY_PASSWORD', 'SPRING_PROFILES_ACTIVE'}
    env.update({k: v for k, v in (config or {}).items()
                if k.startswith(('R03_', 'R11_', 'TEST365ALM_')) or k in spring_keys or k == 'DEMO_WEB_PORT'})
    env['JAVA_HOME'] = str(JAVA_HOME)
    env['PYTHONIOENCODING'] = 'utf-8'
    env['PATH'] = str(JAVA_HOME / 'bin') + os.pathsep + env.get('PATH', '')
    return env


@contextlib.contextmanager
def operation_lock():
    """One local start/stop at a time; OS locks are released even after a crashed launcher."""
    LOCAL.mkdir(parents=True, exist_ok=True)
    with (LOCAL / 'operation.lock').open('a+b') as handle:
        if handle.tell() == 0:
            handle.write(b'0')
            handle.flush()
        handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise RuntimeError('Another demo start/stop is running; no resources touched') from None
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def entrypoint(function):
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    try:
        with operation_lock():
            return function()
    except (OSError, RuntimeError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        return 1


def validate_config(config):
    try:
        ports = {key: port_number(config[key]) for key in ('DEMO_WEB_PORT', 'TEST365ALM_SERVER_PORT',
            'R03_POSTGRES_PORT', 'R03_KEYCLOAK_PORT', 'R11_S3_PORT', 'R11_CLAMD_PORT')}
    except (ValueError, argparse.ArgumentTypeError):
        raise RuntimeError('Invalid port in preserved demo config') from None
    db = f"jdbc:postgresql://127.0.0.1:{ports['R03_POSTGRES_PORT']}/test365alm_demo"
    web = f"http://127.0.0.1:{ports['DEMO_WEB_PORT']}"
    expected = {'R03_DB_NAME': 'test365alm_demo', 'TEST365ALM_DATASOURCE_URL': db, 'SPRING_FLYWAY_URL': db,
        'TEST365ALM_WEB_ORIGIN': web, 'TEST365ALM_OIDC_REDIRECT_URI': web + '/login/oauth2/code/test365alm',
        'TEST365ALM_DEV_BACKEND_URL': f"http://127.0.0.1:{ports['TEST365ALM_SERVER_PORT']}",
        'TEST365ALM_OIDC_ISSUER': f"http://127.0.0.1:{ports['R03_KEYCLOAK_PORT']}/realms/test365alm",
        'TEST365ALM_ASSETS_S3_ENDPOINT': f"http://127.0.0.1:{ports['R11_S3_PORT']}",
        'TEST365ALM_ASSETS_CLAMD_HOST': '127.0.0.1', 'TEST365ALM_ASSETS_CLAMD_PORT': str(ports['R11_CLAMD_PORT']),
        'TEST365ALM_DATASOURCE_USERNAME': 'test365alm_runtime',
        'TEST365ALM_ASSETS_SCANNER_DB_USERNAME': ('oq_demo_scanner_login' if PROJECT.endswith('oq-demo-01') else 'test365alm_scanner'), 'SPRING_PROFILES_ACTIVE': 'oidc'}
    if any(config.get(k) != v for k, v in expected.items()):
        raise RuntimeError('Demo config must use consistent dedicated loopback endpoints, demo database and restricted roles')
    if config.get('TEST365ALM_SERVER_ADDRESS', '127.0.0.1') != '127.0.0.1':
        raise RuntimeError('Demo backend must bind to 127.0.0.1')
    if not config.get('R03_RUN_ID') or any(c not in '0123456789abcdef' for c in config['R03_RUN_ID']):
        raise RuntimeError('Invalid demo ownership run ID')
    realm = json.loads(REALM.read_text(encoding='utf-8'))
    clients = [c for c in realm['clients'] if c['clientId'] == 'test365alm-web']
    if len(clients) != 1 or clients[0]['redirectUris'] != [expected['TEST365ALM_OIDC_REDIRECT_URI']]:
        raise RuntimeError('Demo realm callback does not match selected web port; existing realm preserved')


def prepare(web_port, server_port):
    if ENV.exists() != REALM.exists():
        raise RuntimeError('Partial demo credentials: preserve them and inspect .env.demo and local-evidence/demo/realm.json')
    if ENV.exists():
        config = load_env()
        if int(config['DEMO_WEB_PORT']) != web_port or int(config['TEST365ALM_SERVER_PORT']) != server_port:
            raise RuntimeError('Existing demo ports preserved; use their original values, or stop --delete-data and explicitly archive demo config before recreating')
        return config
    LOCAL.mkdir(parents=True, exist_ok=True)
    # Reuse the supported fresh-environment generator, redirected exclusively to demo-owned files.
    prepare_r03_dev.ENV, prepare_r03_dev.REALM = ENV, REALM
    with contextlib.redirect_stdout(io.StringIO()):
        prepared = prepare_r03_dev.main()
    if prepared != 0:
        raise RuntimeError('Demo credential preparation failed')
    print('Created ignored profile credentials and realm; old demo/R03 config preserved')
    config = load_env()
    config.update({'DEMO_WEB_PORT': str(web_port), 'TEST365ALM_SERVER_PORT': str(server_port),
        'TEST365ALM_WEB_ORIGIN': f'http://127.0.0.1:{web_port}',
        'TEST365ALM_OIDC_REDIRECT_URI': f'http://127.0.0.1:{web_port}/login/oauth2/code/test365alm',
        'TEST365ALM_DEV_BACKEND_URL': f'http://127.0.0.1:{server_port}',
        'R03_DB_NAME': 'test365alm_demo',
        'TEST365ALM_DATASOURCE_URL': 'jdbc:postgresql://127.0.0.1:54339/test365alm_demo',
        'SPRING_FLYWAY_URL': 'jdbc:postgresql://127.0.0.1:54339/test365alm_demo'})
    if PROJECT.endswith('oq-demo-01'):
        config.update(R03_POSTGRES_PORT='54349', R03_KEYCLOAK_PORT='18190', R11_S3_PORT='18433', R11_CLAMD_PORT='13410',
            TEST365ALM_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:54349/test365alm_demo',
            SPRING_FLYWAY_URL='jdbc:postgresql://127.0.0.1:54349/test365alm_demo',
            TEST365ALM_OIDC_ISSUER='http://127.0.0.1:18190/realms/test365alm',
            TEST365ALM_ASSETS_S3_ENDPOINT='http://127.0.0.1:18433', TEST365ALM_ASSETS_CLAMD_PORT='13410',
            TEST365ALM_ASSETS_SCANNER_DB_USERNAME='oq_demo_scanner_login')
    ENV.write_text(''.join(f'{k}={v}\n' for k, v in config.items()), encoding='utf-8')
    realm = json.loads(REALM.read_text(encoding='utf-8'))
    realm['clients'][0]['redirectUris'] = [config['TEST365ALM_OIDC_REDIRECT_URI']]
    realm['clients'][0]['webOrigins'] = [config['TEST365ALM_WEB_ORIGIN']]
    REALM.write_text(json.dumps(realm, ensure_ascii=False, indent=2), encoding='utf-8')
    return config


def identity():
    return {'context': run(['docker', 'context', 'show'], capture=True),
            'engine': run(['docker', 'info', '--format', '{{.ID}}'], capture=True)}


def inventory(config):
    """Check every resource bearing our project label, including stopped containers."""
    result = {}
    for kind, noun in [('containers', 'container'), ('networks', 'network'), ('volumes', 'volume')]:
        listing = ['docker', noun, 'ls', '-q'] + (['-a'] if noun == 'container' else [])
        ids = run([*listing, '--filter', f'label=com.docker.compose.project={PROJECT}'], capture=True).split()
        if ids:
            objects = json.loads(run(['docker', noun, 'inspect', *ids], capture=True))
        else:
            objects = []
        for obj in objects:
            labels = obj.get('Config', {}).get('Labels') if noun == 'container' else obj.get('Labels')
            labels = labels or {}
            if labels.get('com.docker.compose.project') != PROJECT or labels.get('test365alm.r03.run') != config['R03_RUN_ID']:
                raise RuntimeError(f'Unowned {noun} in demo project; refusing all mutation')
            if noun == 'container' and labels.get('com.docker.compose.service') not in SERVICES:
                raise RuntimeError('Unexpected container service in demo project')
        result[kind] = objects
    return result


def make_compose(config):
    raw = run(['docker', 'compose', '--env-file', ENV, '-f', ROOT / 'compose.r03.yaml',
               '-f', ROOT / 'compose.r11.yaml', '-p', PROJECT, 'config', '--format', 'json'],
              capture=True, env=child_env())
    document = json.loads(raw)
    document['name'] = PROJECT
    if PROJECT.endswith('oq-demo-01'):
        from local_browser_assets import prepare_document
        prepare_document(document)  # Only this fresh profile uses SCRAM host initialization.
    # Windows Git checkouts may use CRLF. A Linux shebang with CRLF cannot execute.
    init_script = LOCAL / 'init-db.sh'
    init_script.write_bytes((ROOT / 'tools/r03_init_db.sh').read_bytes().replace(b'\r\n', b'\n'))
    for volume in document['services']['postgres']['volumes']:
        if volume['target'] == '/docker-entrypoint-initdb.d/10-runtime-role.sh':
            volume['source'] = str(init_script)
    kc = document['services']['keycloak']
    for volume in kc['volumes']:
        if volume['target'] == '/opt/keycloak/data/import/realm.json':
            volume['source'] = str(REALM)
    # Persist Keycloak subjects along with PostgreSQL; recreating it must not strand local principals.
    kc['volumes'].append({'type': 'volume', 'source': 'demo-keycloak-data', 'target': '/opt/keycloak/data'})
    document['volumes']['demo-keycloak-data'] = {'name': PROJECT + '_demo-keycloak-data',
        'labels': {'test365alm.r03.run': config['R03_RUN_ID']}}
    COMPOSE.write_text(json.dumps(document, indent=2), encoding='utf-8')
    if os.name == 'posix':
        COMPOSE.chmod(0o600)


def compose(*args):
    return run(['docker', 'compose', '-f', COMPOSE, '-p', PROJECT, *args], capture=True, env=child_env())


def check_port(port, label):
    with socket.socket() as sock:
        # Exclusive bind on Windows detects listeners even when they set SO_REUSEADDR.
        if os.name == 'nt':
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            sock.bind(('127.0.0.1', port))
        except OSError:
            raise RuntimeError(f'{label} port {port} is occupied; choose --web-port/--server-port or edit unused .env.demo service ports; the owner will not be stopped') from None


def check_ports(config, resources, processes):
    ports = [(int(config['DEMO_WEB_PORT']), 'web', None),
             (int(config['TEST365ALM_SERVER_PORT']), 'server', None),
             (int(config['R03_POSTGRES_PORT']), 'postgres', 5432),
             (int(config['R03_KEYCLOAK_PORT']), 'keycloak', 8080),
             (int(config['R11_S3_PORT']), 'asset-store', 8333),
             (int(config['R11_CLAMD_PORT']), 'asset-scanner', 3310)]
    if len({p for p, _, _ in ports}) != len(ports):
        raise RuntimeError('Demo ports must be distinct')
    for port, name, internal in ports:
        if name in processes and process_owned(processes[name], config, name):
            continue
        owned = False
        for container in resources['containers']:
            labels = container['Config']['Labels']
            if labels['com.docker.compose.service'] == name and container['State']['Running']:
                bindings = container['NetworkSettings']['Ports'].get(str(internal) + '/tcp', []) or []
                owned = any(b.get('HostIp') == '127.0.0.1' and b.get('HostPort') == str(port) for b in bindings)
        if not owned:
            check_port(port, name)


def process_owned(pid, config, role):
    if os.name == 'nt':
        command = run(['powershell.exe', '-NoProfile', '-Command',
            f'(Get-CimInstance Win32_Process -Filter "ProcessId={int(pid)}").CommandLine'], capture=True)
    else:
        try:
            command = Path(f'/proc/{int(pid)}/cmdline').read_bytes().replace(b'\0', b' ').decode()
        except FileNotFoundError:
            return False
    return (str(ROOT / 'tools' / 'demo_process.py') in command
            and config['R03_RUN_ID'] in command and f' {role} ' in command)


def save_state(config, processes):
    resources = inventory(config)
    state = dict(identity(), run=config['R03_RUN_ID'], workspace=str(ROOT), processes=processes,
                 containers=[c['Id'] for c in resources['containers']],
                 networks=[n['Id'] for n in resources['networks']],
                 volumes=[v['Name'] for v in resources['volumes']])
    STATE.write_text(json.dumps(state, indent=2), encoding='utf-8')
    return state


def validate_state(config):
    if not STATE.exists():
        return {}
    state = json.loads(STATE.read_text(encoding='utf-8'))
    current = identity()
    if any(state.get(k) != v for k, v in current.items()) or state['run'] != config['R03_RUN_ID'] or state['workspace'] != str(ROOT):
        raise RuntimeError('Demo state Docker engine/context/workspace/run mismatch')
    resources = inventory(config)
    for key, field in [('containers', 'Id'), ('networks', 'Id'), ('volumes', 'Name')]:
        if not {r[field] for r in resources[key]}.issubset(set(state[key])):
            raise RuntimeError('Demo resources changed outside the startup script; refusing mutation')
    return state.get('processes', {})


def http_ready(url):
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=3) as response:
            return response.status == 200
    except OSError:
        return False


def wait_for(label, predicate, seconds=300):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            print('Healthy: ' + label, flush=True)
            return
        time.sleep(2)
    raise RuntimeError(f'{label} was not healthy within {seconds}s; inspect ignored local-evidence/demo logs locally')


def start_process(role, config):
    log = (LOCAL / (role + '.log')).open('a', encoding='utf-8')
    args = [sys.executable, str(ROOT / 'tools' / 'demo_process.py'), role, config['R03_RUN_ID']]
    kwargs = {'creationflags': subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
    try:
        process = subprocess.Popen(args, cwd=ROOT, env=child_env(config), stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=log, **kwargs)
    finally:
        log.close()
    print(f'Started demo {role} supervisor PID {process.pid}')
    return process.pid


def bootstrap(config, ids):
    ids = [str(uuid.UUID(value)) for value in ids]
    # Only scope and tenant membership are bootstrap SQL; project and all business records use HTTP.
    members = ','.join(f"('{principal}'::uuid, ARRAY['{'TENANT_ADMIN' if i == 0 else 'MEMBER'}']::text[])" for i, principal in enumerate(ids))
    sql = f"""BEGIN;
INSERT INTO tenant(id,code,name) VALUES ('{TENANT}','DEMO-OQ','OQ 演示租户') ON CONFLICT (id) DO NOTHING;
INSERT INTO domain(id,tenant_id,name) VALUES ('{DOMAIN}','{TENANT}','DEFAULT') ON CONFLICT (id) DO NOTHING;
INSERT INTO tenant_member(tenant_id,principal_id,roles)
SELECT '{TENANT}',m.id,m.roles FROM (VALUES {members}) AS m(id,roles)
ON CONFLICT (tenant_id,principal_id) DO NOTHING;
COMMIT;
"""
    inventory(config)
    compose_id = compose('ps', '-q', 'postgres')
    if not compose_id:
        raise RuntimeError('Owned postgres container not available')
    run(['docker', 'exec', '-i', compose_id, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
         '-U', config['R03_MIGRATION_USER'], '-d', config['R03_DB_NAME']], data=sql, capture=True)


def port_number(value):
    port = int(value)
    if not 1024 <= port <= 65535:
        raise argparse.ArgumentTypeError('port must be 1024..65535')
    return port


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--web-port', type=port_number, default=15273 if PROJECT.endswith('oq-demo-01') else 15173)
    parser.add_argument('--server-port', type=port_number, default=18183 if PROJECT.endswith('oq-demo-01') else 18083)
    args = parser.parse_args()
    try:
        if not (JAVA_HOME / 'bin' / 'java.exe').exists() and os.name == 'nt':
            raise RuntimeError('Java 21 missing at documented JAVA_HOME')
        config = prepare(args.web_port, args.server_port)
        validate_config(config)
        processes = validate_state(config)
        resources = inventory(config)
        check_ports(config, resources, processes)
        make_compose(config)
        # Write an ownership checkpoint before up as well: failed up may create only some services.
        save_state(config, processes)
        print('Starting only Compose project ' + PROJECT, flush=True)
        try:
            compose('up', '-d', *SERVICES)
        finally:
            save_state(config, processes)
        wait_for('PostgreSQL', lambda: postgres_ready(config))
        wait_for('Keycloak OIDC', lambda: http_ready(config['TEST365ALM_OIDC_ISSUER'] + '/.well-known/openid-configuration'))
        wait_for('private S3 + real clamd benign scan', lambda: assets_ready(config['TEST365ALM_ASSETS_S3_ENDPOINT'], '127.0.0.1', int(config['R11_CLAMD_PORT'])), seconds=420)
        env = child_env(config)
        if not process_owned(processes.get('server', 0), config, 'server'):
            print('Building server with Java 21...', flush=True)
            mvn = shutil.which('mvn.cmd') or shutil.which('mvn')
            if not mvn:
                raise RuntimeError('System Maven is required')
            with (LOCAL / 'build.log').open('w', encoding='utf-8') as build_log:
                build = subprocess.run([mvn, '-B', '-ntp', '-DskipTests', 'package'], cwd=ROOT / 'apps/server',
                                       env=env, stdout=build_log, stderr=subprocess.STDOUT)
            if build.returncode:
                raise RuntimeError('Maven package failed; inspect local-evidence/demo/build.log')
            processes['server'] = start_process('server', config)
            save_state(config, processes)
        wait_for('backend /health/ready', lambda: http_ready(config['TEST365ALM_DEV_BACKEND_URL'] + '/health/ready'))
        if not process_owned(processes.get('web', 0), config, 'web'):
            if not (ROOT / 'apps/web/node_modules/vite/bin/vite.js').exists():
                print('Installing web dependencies with npm ci...', flush=True)
                npm = shutil.which('npm.cmd') or shutil.which('npm')
                if not npm:
                    raise RuntimeError('npm is required')
                run([npm, 'ci'], cwd=ROOT / 'apps/web', env=env, capture=True)
            processes['web'] = start_process('web', config)
            save_state(config, processes)
        wait_for('frontend and API proxy', lambda: http_ready(config['TEST365ALM_WEB_ORIGIN']) and http_ready(config['TEST365ALM_WEB_ORIGIN'] + '/health/ready'))
        if PROJECT.endswith('oq-demo-01'):
            # Official operator tool, both real SCRAM connections, no role/password/HBA reset.
            from local_browser_assets import provision
            services = {c['Config']['Labels']['com.docker.compose.service']: {'id': c['Id']} for c in inventory(config)['containers']}
            provision(config, services, dict(env, TEST365ALM_DOCKER_CONTEXT=identity()['context']), lambda: validate_state(config))
            checkpoint = LOCAL / 'scenario.json'
            if checkpoint.exists():
                from oq_demo import inspect
                from demo_seed import Api
                api = Api(config['TEST365ALM_WEB_ORIGIN'], config['TEST365ALM_OIDC_ISSUER'])
                api.login(config['R03_TEST_USER'], config['R03_TEST_USER_PASSWORD'])
                result = inspect(api, json.loads(checkpoint.read_text(encoding='utf-8')))
            else:
                result = {'scenario': 'OQ-DEMO-01', 'mode': 'read-only startup', 'checkpointPresent': False}
        else:
            result = seed(config, lambda ids: bootstrap(config, ids), LOCAL / 'seed-complete.json')
        (LOCAL / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Demo ready: ' + config['TEST365ALM_WEB_ORIGIN'])
        print('Accounts: r03-user (reviewer/admin), r03-member (author/executor), r03-viewer; passwords only in ignored ' + ENV.name)
        return 0
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        return 1


def postgres_ready(config):
    try:
        compose('exec', '-T', 'postgres', 'pg_isready', '-U', config['R03_MIGRATION_USER'], '-d', config['R03_DB_NAME'])
        return True
    except RuntimeError:
        return False


if __name__ == '__main__':
    select_profile_args()
    raise SystemExit(entrypoint(main))
