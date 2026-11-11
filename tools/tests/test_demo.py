"""Offline safety and repeatability checks; never connect to Docker or developer databases."""
import copy
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import demo_seed
import demo_up
import demo_down


class DemoSafetyTests(unittest.TestCase):
    def test_occupied_port_rejected_without_stopping_owner(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            with self.assertRaisesRegex(RuntimeError, 'occupied'):
                demo_up.check_port(listener.getsockname()[1], 'web')
            self.assertNotEqual(listener.fileno(), -1)

    def test_ports_must_be_distinct(self):
        config = {'DEMO_WEB_PORT': '15173', 'TEST365ALM_SERVER_PORT': '15173',
                  'R03_POSTGRES_PORT': '54339', 'R03_KEYCLOAK_PORT': '18090',
                  'R11_S3_PORT': '18333', 'R11_CLAMD_PORT': '13310'}
        with self.assertRaisesRegex(RuntimeError, 'distinct'):
            demo_up.check_ports(config, {'containers': []}, {})

    def test_unowned_resource_blocks_mutation_including_stopped_container(self):
        calls = []
        def fake_run(args, **kwargs):
            calls.append(args)
            if args[2] == 'ls':
                self.assertIn('-a', args)
                return 'container-id'
            return json.dumps([{'Config': {'Labels': {'com.docker.compose.project': demo_up.PROJECT,
                'test365alm.r03.run': 'someone-else', 'com.docker.compose.service': 'postgres'}}}])
        with patch.object(demo_up, 'run', side_effect=fake_run):
            with self.assertRaisesRegex(RuntimeError, 'Unowned'):
                demo_up.inventory({'R03_RUN_ID': 'mine'})
        self.assertTrue(all(args[2] in ('ls', 'inspect') for args in calls))

    def test_child_environment_drops_database_jvm_and_compose_overrides(self):
        with patch.dict(os.environ, {'SPRING_CONFIG_LOCATION': 'evil', 'JAVA_TOOL_OPTIONS': 'evil',
                         'PGHOST': 'production', 'COMPOSE_FILE': 'other.yaml', 'PATH': 'original'}, clear=True):
            env = demo_up.child_env({'TEST365ALM_SERVER_PORT': '18083', 'JAVA_TOOL_OPTIONS': 'evil',
                                     'SPRING_CONFIG_LOCATION': 'evil'})
        for key in ('SPRING_CONFIG_LOCATION', 'JAVA_TOOL_OPTIONS', 'PGHOST', 'COMPOSE_FILE'):
            self.assertNotIn(key, env)
        self.assertEqual(env['TEST365ALM_SERVER_PORT'], '18083')

    def test_config_preserves_r03_and_existing_demo_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            env, realm = root / '.env.demo', root / 'demo/realm.json'
            r03 = root / '.env.r03'
            r03.write_text('untouched', encoding='utf-8')
            with patch.multiple(demo_up, ENV=env, REALM=realm, LOCAL=realm.parent), \
                 patch.multiple(demo_up.prepare_r03_dev, ENV=env, REALM=realm):
                first = demo_up.prepare(15173, 18083)
                bytes_before = (env.read_bytes(), realm.read_bytes())
                second = demo_up.prepare(15173, 18083)
                self.assertEqual(first, second)
                self.assertEqual(bytes_before, (env.read_bytes(), realm.read_bytes()))
                self.assertEqual(r03.read_text(), 'untouched')
                self.assertEqual(json.loads(realm.read_text())['clients'][0]['redirectUris'],
                                 ['http://127.0.0.1:15173/login/oauth2/code/test365alm'])
                with self.assertRaisesRegex(RuntimeError, 'ports preserved'):
                    demo_up.prepare(15174, 18083)

    def test_partial_config_is_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, realm = Path(temporary) / '.env.demo', Path(temporary) / 'realm.json'
            env.write_text('existing', encoding='utf-8')
            with patch.multiple(demo_up, ENV=env, REALM=realm):
                with self.assertRaisesRegex(RuntimeError, 'Partial'):
                    demo_up.prepare(15173, 18083)
            self.assertEqual(env.read_text(), 'existing')

    def test_foreign_datasource_and_mismatched_callback_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            env, realm = Path(temporary) / '.env.demo', Path(temporary) / 'realm.json'
            with patch.multiple(demo_up, ENV=env, REALM=realm, LOCAL=Path(temporary)), \
                 patch.multiple(demo_up.prepare_r03_dev, ENV=env, REALM=realm):
                config = demo_up.prepare(15173, 18083)
                demo_up.validate_config(config)
                config['TEST365ALM_DATASOURCE_URL'] = 'jdbc:postgresql://production/company'
                with self.assertRaisesRegex(RuntimeError, 'dedicated loopback'):
                    demo_up.validate_config(config)

    def test_windows_lf_init_copy_does_not_change_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            local = Path(temporary)
            document = {'services': {'postgres': {'volumes': [{'target': '/docker-entrypoint-initdb.d/10-runtime-role.sh', 'source': 'original'}]},
                        'keycloak': {'volumes': [{'target': '/opt/keycloak/data/import/realm.json', 'source': 'r03'}]}}, 'volumes': {}}
            original = (demo_up.ROOT / 'tools/r03_init_db.sh').read_bytes()
            with patch.multiple(demo_up, LOCAL=local, COMPOSE=local / 'compose.json'), \
                 patch.object(demo_up, 'run', return_value=json.dumps(document)):
                demo_up.make_compose({'R03_RUN_ID': 'mine'})
            self.assertNotIn(b'\r', (local / 'init-db.sh').read_bytes())
            self.assertEqual((demo_up.ROOT / 'tools/r03_init_db.sh').read_bytes(), original)

    def test_parallel_operation_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(demo_up, 'LOCAL', Path(temporary)):
            with demo_up.operation_lock():
                with self.assertRaisesRegex(RuntimeError, 'Another demo'):
                    with demo_up.operation_lock():
                        self.fail('Concurrent operation admitted')

    def test_state_engine_mismatch_refuses_before_resource_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / 'state.json'
            state.write_text(json.dumps({'context': 'other', 'engine': 'other'}))
            with patch.object(demo_up, 'STATE', state), patch.object(demo_up, 'identity', return_value={'engine': 'current'}), \
                 patch.object(demo_up, 'inventory') as inventory:
                with self.assertRaisesRegex(RuntimeError, 'mismatch'):
                    demo_up.validate_state({'R03_RUN_ID': 'run'})
                inventory.assert_not_called()

    def test_demo_data_has_exact_identifiers_and_three_steps(self):
        cases = list(demo_seed.cases())
        self.assertEqual([c['title'] for c in cases], [f'{demo_seed.SET_NAME}-{i}' for i in range(1, 15)])
        self.assertTrue(all(2 <= len(c['steps']) <= 4 for c in cases))
        self.assertTrue(any('VL01NO' in s['action'] for c in cases for s in c['steps']))

    def test_secure_keycloak_cookie_is_only_allowed_on_exact_local_origin(self):
        policy = demo_seed.LoopbackCookiePolicy('http://127.0.0.1:18090/realms/test365alm')
        cookie = type('Cookie', (), {'secure': True})()
        self.assertTrue(policy.return_ok_secure(cookie, urllib.request.Request('http://127.0.0.1:18090/realms/test365alm/')))
        self.assertFalse(policy.return_ok_secure(cookie, urllib.request.Request('http://127.0.0.1:15173/')))
        self.assertFalse(policy.return_ok_secure(cookie, urllib.request.Request('http://example.com/')))
        with self.assertRaises(ValueError):
            demo_seed.LoopbackCookiePolicy('http://example.com/realms/test365alm')

    def test_down_without_manifest_does_not_touch_resources(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(demo_down, 'STATE', Path(temporary) / 'missing'), \
             patch.object(demo_down, 'ENV', Path(temporary) / 'missing-env'), patch.object(sys, 'argv', ['demo_down.py']), \
             patch.object(demo_down, 'run') as command:
            self.assertEqual(demo_down.main(), 1)
            command.assert_not_called()

    def test_down_defaults_to_preserving_volumes(self):
        resources = {'containers': [{'Id': 'demo-container'}], 'networks': [{'Id': 'demo-net'}], 'volumes': [{'Name': 'demo-data'}]}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'exists'
            path.touch()
            with patch.multiple(demo_down, ENV=path, STATE=path), patch.object(sys, 'argv', ['demo_down.py']), \
                 patch.object(demo_down, 'load_env', return_value={}), patch.object(demo_down, 'validate_state', return_value={}), \
                 patch.object(demo_down, 'inventory', return_value=resources), patch.object(demo_down, 'save_state'), \
                 patch.object(demo_down, 'run') as command:
                self.assertEqual(demo_down.main(), 0)
                command.assert_called_once_with(['docker', 'stop', 'demo-container'], capture=True)

    def test_explicit_delete_only_removes_verified_demo_ids(self):
        resources = {'containers': [{'Id': 'demo-container'}], 'networks': [{'Id': 'demo-net'}], 'volumes': [{'Name': 'demo-data'}]}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'exists'
            path.touch()
            with patch.multiple(demo_down, ENV=path, STATE=path), patch.object(sys, 'argv', ['demo_down.py', '--delete-data']), \
                 patch.object(demo_down, 'load_env', return_value={}), patch.object(demo_down, 'validate_state', return_value={}), \
                 patch.object(demo_down, 'inventory', return_value=resources), patch.object(demo_down, 'save_state'), \
                 patch.object(demo_down, 'run') as command:
                self.assertEqual(demo_down.main(), 0)
                self.assertEqual([call.args[0] for call in command.call_args_list], [
                    ['docker', 'stop', 'demo-container'], ['docker', 'rm', 'demo-container'],
                    ['docker', 'network', 'rm', 'demo-net'], ['docker', 'volume', 'rm', 'demo-data']])

    def test_stale_process_pid_never_kills_an_unrelated_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'exists'
            path.touch()
            with patch.multiple(demo_down, ENV=path, STATE=path), patch.object(sys, 'argv', ['demo_down.py']), \
                 patch.object(demo_down, 'load_env', return_value={}), patch.object(demo_down, 'validate_state', return_value={'web': 99}), \
                 patch.object(demo_down, 'inventory', return_value={'containers': [], 'networks': [], 'volumes': []}), \
                 patch.object(demo_down, 'process_owned', return_value=False), patch.object(demo_down, 'save_state'), \
                 patch.object(demo_down, 'run') as command:
                self.assertEqual(demo_down.main(), 0)
                command.assert_not_called()


class FakeApi:
    """A stateful HTTP double, so repeat runs must discover saved objects without writes."""
    data = None
    writes = 0

    def __init__(self, *args):
        pass

    def login(self, username, password):
        self.principal = username
        return username

    def call(self, path, body=None, method=None, key=None, version=None):
        data = type(self).data
        if body is not None:
            type(self).writes += 1
        if '/permissions?' in path:
            role = {'admin': 'PROJECT_ADMIN', 'member': 'PROJECT_MEMBER', 'viewer': 'PROJECT_VIEWER'}[self.principal]
            return {'roles': [role]}
        if path.startswith('/api/v1/projects?'):
            return copy.deepcopy(data['projects'])
        if path == '/api/v1/projects':
            project = dict(body, id='project')
            data['projects'].append(project)
            return copy.deepcopy(project)
        if path.endswith('/members'):
            return copy.deepcopy(data['members'])
        if '/members/' in path:
            data['members'].append(dict(body, principalId=path.rsplit('/', 1)[1]))
            return body
        if '/folders?kind=' in path:
            kind = path.split('kind=')[1]
            return copy.deepcopy([f for f in data['folders'] if f['kind'] == kind])
        if path.endswith('/folders'):
            folder = dict(body, id='folder-' + str(len(data['folders'])), rowVersion=1)
            data['folders'].append(folder)
            return copy.deepcopy(folder)
        if '/folder-placements/' in path:
            location_key = path.split('/folder-placements/')[1]
            old = data['placements'].get(location_key, {'objectId': location_key.split('/')[-1], 'folderId': None, 'rowVersion': 1})
            if body is not None:
                if version != old['rowVersion']:
                    raise AssertionError('Stale placement version')
                old = dict(old, folderId=body['folderId'], rowVersion=version + 1)
                data['placements'][location_key] = old
            return copy.deepcopy(old)
        if '/tests?' in path:
            return {'items': [{'id': c['id'], 'title': c['currentRevision']['title'],
                               'currentRevisionId': c['currentRevision']['id']} for c in data['tests']]}
        if '/tests/' in path:
            return copy.deepcopy(next(c for c in data['tests'] if c['id'] == path.rsplit('/', 1)[1]))
        if path.endswith('/tests'):
            case = {'id': str(len(data['tests'])), 'currentRevision': dict(body, id=str(uuid.uuid4()))}
            data['tests'].append(case)
            return copy.deepcopy(case)
        if '/test-sets/page' in path:
            return {'items': copy.deepcopy(data['sets'])}
        if path.endswith('/test-sets'):
            result = dict(body, id='set')
            data['sets'].append(result)
            return result
        if '/instances/page' in path:
            return {'items': copy.deepcopy(data['instances'])}
        if path.endswith('/instances'):
            result = dict(body, id='instance-' + body['testCaseId'])
            data['instances'].append(result)
            return result
        if '/runs/page?' in path:
            return {'items': [copy.deepcopy(d['run']) for d in data['runs'].values()]}
        if '/runs/summary?' in path:
            return {'totalInstances': 14, 'activeAttempts': 1, 'latestCompletedPass': 11, 'latestCompletedFail': 2}
        if path.endswith('/runs'):
            index = int(body['testInstanceId'].split('-')[-1])
            case = data['tests'][index]
            detail = {'run': dict(body, id='run-' + str(index)), 'currentAttempt': {'id': 'attempt', 'rowVersion': 1,
                'status': 'RUNNING', 'conclusion': None,
                'steps': [dict(s, stepKey=str(i), conclusion='NOT_RUN', actualResult='', rowVersion=1)
                          for i, s in enumerate(case['currentRevision']['steps'])]}}
            data['runs'][detail['run']['id']] = detail
            return copy.deepcopy(detail)
        if '/runs/' in path:
            runid = path.split('/runs/')[1].split('/')[0]
            detail = data['runs'][runid]
            attempt = detail['currentAttempt']
            if '/steps/' in path:
                step = attempt['steps'][int(path.rsplit('/', 1)[1])]
                step.update(actualResult=body['actualResult'], conclusion=body['conclusion'])
                step['rowVersion'] += 1
                attempt['rowVersion'] += 1
                return copy.deepcopy(attempt)
            if path.endswith('/finish'):
                attempt.update(status='FINISHED', conclusion='FAIL' if any(s['conclusion'] == 'FAIL' for s in attempt['steps']) else 'PASS')
                return copy.deepcopy(attempt)
            return copy.deepcopy(detail)
        if '/defects?' in path:
            return {'items': copy.deepcopy(data['defects'])}
        if path.endswith('/defects/from-step'):
            defect = {'currentRevision': body}
            data['defects'].append(defect)
            return defect
        raise AssertionError(path)


class DemoSeedTests(unittest.TestCase):
    def setUp(self):
        FakeApi.data = {key: [] for key in ('projects', 'members', 'tests', 'sets', 'instances', 'defects', 'folders')}
        FakeApi.data['placements'] = {}
        FakeApi.data['runs'] = {}
        FakeApi.writes = 0
        self.config = {'TEST365ALM_WEB_ORIGIN': 'http://127.0.0.1:15173', 'TEST365ALM_OIDC_ISSUER': 'http://127.0.0.1:18090/realms/test365alm',
                       'R03_TEST_USER': 'admin', 'R03_TEST_USER_PASSWORD': 'synthetic',
                       'R03_MEMBER_USER': 'member', 'R03_MEMBER_PASSWORD': 'synthetic',
                       'R03_VIEWER_USER': 'viewer', 'R03_VIEWER_PASSWORD': 'synthetic'}

    def test_first_seed_and_second_run_produce_no_duplicate_or_extra_writes(self):
        with patch.object(demo_seed, 'Api', FakeApi):
            first = demo_seed.seed(self.config, lambda ids: None)
            writes = FakeApi.writes
            second = demo_seed.seed(self.config, lambda ids: None)
        self.assertEqual(first, second)
        self.assertEqual(FakeApi.writes, writes)
        self.assertEqual(first['runs'], {'PASS': 11, 'FAIL': 2, 'RUNNING': 1})
        self.assertTrue(first['independentActualAndConclusion'])

    def test_interrupted_seed_resumes_existing_case_instead_of_creating_duplicate(self):
        with patch.object(demo_seed, 'Api', FakeApi):
            demo_seed.seed(self.config, lambda ids: None)
            FakeApi.data['runs'].pop('run-13')
            FakeApi.data['instances'].pop()
            demo_seed.seed(self.config, lambda ids: None)
        self.assertEqual(len(FakeApi.data['tests']), 14)
        self.assertEqual(len(FakeApi.data['instances']), 14)
        self.assertEqual(len(FakeApi.data['runs']), 14)

    def test_seed_organizes_cases_and_set_without_changing_revisions(self):
        with patch.object(demo_seed, 'Api', FakeApi):
            demo_seed.seed(self.config, lambda ids: None)
        for kind, count in [('TEST_PLAN', 14), ('TEST_LAB', 1)]:
            folders = [f for f in FakeApi.data['folders'] if f['kind'] == kind]
            parent = next(f for f in folders if f['name'] == 'OQ运行确认')
            child = next(f for f in folders if f['name'] == demo_seed.SET_NAME)
            self.assertEqual(child['parentId'], parent['id'])
            self.assertEqual(sum(p['folderId'] == child['id'] for p in FakeApi.data['placements'].values()), count)
        self.assertEqual(len(FakeApi.data['tests']), 14)

    def test_partial_seed_refuses_to_overwrite_manual_organization(self):
        with patch.object(demo_seed, 'Api', FakeApi):
            demo_seed.seed(self.config, lambda ids: None)
            FakeApi.data['placements']['TEST_PLAN/0']['folderId'] = 'manual-folder'
            with self.assertRaisesRegex(RuntimeError, 'organization'):
                demo_seed.seed(self.config, lambda ids: None)
        self.assertEqual(FakeApi.data['placements']['TEST_PLAN/0']['folderId'], 'manual-folder')

    def test_completed_seed_preserves_manual_organization_without_writes(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(demo_seed, 'Api', FakeApi):
            checkpoint = Path(temporary) / 'complete.json'
            demo_seed.seed(self.config, lambda ids: None, checkpoint)
            FakeApi.data['placements']['TEST_PLAN/0']['folderId'] = 'manual-folder'
            writes = FakeApi.writes
            demo_seed.seed(self.config, lambda ids: None, checkpoint)
        self.assertEqual(FakeApi.writes, writes)
        self.assertEqual(FakeApi.data['placements']['TEST_PLAN/0']['folderId'], 'manual-folder')

    def test_completed_seed_does_not_overwrite_a_users_new_running_attempt(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(demo_seed, 'Api', FakeApi):
            checkpoint = Path(temporary) / 'complete.json'
            demo_seed.seed(self.config, lambda ids: None, checkpoint)
            FakeApi.data['runs']['run-0']['currentAttempt'].update(status='RUNNING', conclusion=None)
            writes = FakeApi.writes
            result = demo_seed.seed(self.config, lambda ids: None, checkpoint)
            self.assertEqual(result['runs']['RUNNING'], 2)
            self.assertEqual(FakeApi.writes, writes)


if __name__ == '__main__':
    unittest.main()
