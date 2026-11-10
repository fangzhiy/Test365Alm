"""Synthetic A03 fixture, written through the public OIDC/CSRF business APIs."""
from __future__ import annotations

import html.parser
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid

SET_NAME = 'GS-VDZ50118-OQP-01-A03'
TENANT = str(uuid.uuid5(uuid.NAMESPACE_URL, 'test365alm-demo/tenant'))
DOMAIN = str(uuid.uuid5(uuid.NAMESPACE_URL, 'test365alm-demo/domain'))


class LoginForm(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'form' and attrs.get('id') == 'kc-form-login':
            self.action = attrs.get('action')


class Api:
    def __init__(self, base, issuer):
        self.base, self.issuer = base, issuer
        policy = LoopbackCookiePolicy(issuer)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(policy)))

    def login(self, username, password):
        with self.opener.open(self.base + '/oauth2/authorization/test365alm', timeout=30) as response:
            form = LoginForm()
            form.feed(response.read().decode())
        action = form.action
        if not action or not action.startswith(self.issuer + '/login-actions/'):
            raise RuntimeError('Expected local Keycloak login form; no credentials sent')
        body = urllib.parse.urlencode({'username': username, 'password': password, 'credentialId': ''}).encode()
        with self.opener.open(action, body, timeout=30) as response:
            response.read()
        self.principal = self.call('/api/v1/me')['id']
        return self.principal

    def call(self, path, body=None, method=None, key=None, version=None):
        headers = {'Accept': 'application/json'}
        if body is not None:
            csrf = self.call('/api/v1/csrf')
            headers.update({'Content-Type': 'application/json', 'Origin': self.base,
                            csrf['headerName']: csrf['token']})
        if key:
            headers['Idempotency-Key'] = 'demo-a03-v1-' + key
        if version is not None:
            headers['If-Match'] = f'"{version}"'
        request = urllib.request.Request(self.base + path,
            None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # Never include a response body, login URL, cookie, or CSRF value in diagnostics.
            raise RuntimeError(f'API {request.get_method()} {path.split("?")[0]} returned HTTP {error.code}') from None


class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    """Match browser localhost secure-cookie behavior, solely for this local Keycloak origin."""
    def __init__(self, issuer):
        super().__init__()
        self.local_origin = urllib.parse.urlsplit(issuer).netloc
        if urllib.parse.urlsplit(issuer).hostname != '127.0.0.1':
            raise ValueError('Demo login only supports explicit loopback Keycloak')

    def return_ok_secure(self, cookie, request):
        target = urllib.parse.urlsplit(request.full_url)
        if target.scheme == 'http' and target.netloc == self.local_origin:
            return True
        return super().return_ok_secure(cookie, request)


def cases():
    scenarios = [
        ('创建单行交货单', 'VL01NO', '录入单行交货数据并保存', '成功创建单行交货单'),
        ('创建多行交货单', 'VL01NO', '录入两行不同物料并保存', '两行物料数量和交货地点正确'),
        ('修改交货数量', 'VL02N', '修改未过账交货单的数量并保存', '交货数量更新且未超过订单可交数量'),
        ('显示交货单', 'VL03N', '查询交货单抬头、项目和单据流', '抬头、物料与订单引用一致'),
        ('拣配确认', 'VL02N', '录入拣配数量并保存', '拣配状态显示已完成'),
        ('批次与有效期核对', 'VL02N', '选择已放行批次并核对有效期', '批次、有效期与交货要求一致'),
        ('交货过账', 'VL02N', '对已完成拣配的交货单执行发货过账', '生成物料凭证并更新库存'),
        ('交货凭证打印', 'VL03N', '预览并打印交货凭证', '打印内容含交货号、批次及数量'),
        ('退货订单创建', 'VA01', '参考原销售单创建退货订单', '退货订单引用原单且数量正确'),
        ('退货交货创建', 'VL01NO', '参考退货订单创建退货交货单', '退货物料及数量与退货订单一致'),
        ('退货收货过账', 'VL02N', '对退货交货单执行收货过账', '退货入库生成凭证且库存增加'),
        ('超量退货校验', 'VA01', '录入超过原交货数量的退货数量', '系统拒绝超量并给出明确提示'),
        ('退货原因必填校验', 'VA01', '清空退货原因后尝试保存', '系统拒绝保存并提示退货原因必填'),
        ('交货退货单据流复核', 'VL03N', '核对原订单、交货、退货及物料凭证链路', '单据链完整且数量对应'),
    ]
    for number, (name, transaction, action, expected) in enumerate(scenarios, 1):
        yield {'testType': 'MANUAL', 'title': f'{SET_NAME}-{number}',
               'description': f'{name}。合成 OQ 演示样例，不是客户历史记录。',
               'preconditions': '使用隔离 SAP 验证环境；测试物料、客户与订单已准备，具备相应事务权限。',
               'steps': [
                   {'ordinal': 1, 'action': '登录并准备：登录 SAP 验证环境，核对测试订单和物料。', 'expected': '成功登录，测试数据可用'},
                   {'ordinal': 2, 'action': f'{name}：执行 {transaction}，{action}。', 'expected': expected},
                   {'ordinal': 3, 'action': '复核记录：重新查询单据并记录单号及操作结果。', 'expected': '查询结果与操作记录一致'},
               ]}


def demo_folder(api, root, kind):
    """Discover durable folder identities when resuming an interrupted fresh seed."""
    nodes = api.call(root + '/folders?kind=' + kind)
    parent = None
    for index, name in enumerate(('OQ运行确认', SET_NAME)):
        node = next((f for f in nodes if f['parentId'] == parent and f['name'] == name), None)
        if node is None:
            node = api.call(root + '/folders', {'kind': kind, 'parentId': parent,
                            'name': name, 'sortOrder': 0}, key=f'folder-{kind}-{index}')
        parent = node['id']
    return parent


def place_demo_object(api, root, kind, object_id, folder_id):
    path = root + '/folder-placements/' + kind + '/' + object_id
    location = api.call(path)
    if location['folderId'] == folder_id:
        return
    if location['folderId'] is not None:
        raise RuntimeError('Demo organization was changed; refusing to overwrite it')
    api.call(path, {'folderId': folder_id}, method='PUT', version=location['rowVersion'])


def seed(config, bootstrap, checkpoint=None):
    base = config['TEST365ALM_WEB_ORIGIN']
    issuer = config['TEST365ALM_OIDC_ISSUER']
    accounts = [('R03_TEST_USER', 'R03_TEST_USER_PASSWORD', 'PROJECT_ADMIN'),
                ('R03_MEMBER_USER', 'R03_MEMBER_PASSWORD', 'PROJECT_MEMBER'),
                ('R03_VIEWER_USER', 'R03_VIEWER_PASSWORD', 'PROJECT_VIEWER')]
    sessions = []
    for user, password, role in accounts:
        api = Api(base, issuer)
        api.login(config[user], config[password])
        sessions.append((api, role))
    bootstrap([api.principal for api, _ in sessions])
    admin = sessions[0][0]
    projects = admin.call('/api/v1/projects?tenantId=' + TENANT)
    matches = [p for p in projects if p['code'] == 'JINSAI-OQ-DEMO']
    if not matches:
        project = admin.call('/api/v1/projects', {'tenantId': TENANT, 'domainId': DOMAIN,
                            'code': 'JINSAI-OQ-DEMO', 'name': 'jinsai 验证测试'})
    else:
        project = matches[0]
    root = '/api/v1/projects/' + project['id']
    if checkpoint is not None and checkpoint.exists():
        completed = json.loads(checkpoint.read_text(encoding='utf-8'))
        if completed.get('projectId') == project['id']:
            print('Seed: completed demo preserved; checking existing data only')
            return verify(admin, root, completed['setId'], sessions)
    members = {m['principalId']: m for m in admin.call(root + '/members')}
    for api, role in sessions[1:]:
        member = members.get(api.principal)
        if member is None:
            admin.call(root + '/members/' + api.principal,
                {'roles': [role], 'authorizationVersion': 0}, method='PUT')
        elif member['roles'] != [role]:
            raise RuntimeError('Demo project membership was changed; refusing to overwrite it')

    # Resume partial seeds by querying durable objects. Never rely solely on 24h idempotency retention.
    existing = {t['title']: t for t in admin.call(root + '/tests?limit=100')['items']}
    sets = admin.call(root + '/test-sets/page?limit=100')['items']
    test_set = next((s for s in sets if s['name'] == SET_NAME), None)
    if test_set is None:
        test_set = admin.call(root + '/test-sets', {'name': SET_NAME, 'description': 'SAP 交货与退货 OQ 合成演示'}, key='set')
    plan_folder = demo_folder(admin, root, 'TEST_PLAN')
    lab_folder = demo_folder(admin, root, 'TEST_LAB')
    place_demo_object(admin, root, 'TEST_LAB', test_set['id'], lab_folder)
    setroot = root + '/test-sets/' + test_set['id']
    instances = {i['testCaseId']: i for i in admin.call(setroot + '/instances/page?limit=100')['items']}
    runs = admin.call(root + '/runs/page?testSetId=' + test_set['id'] + '&limit=100')['items']
    by_instance = {r['testInstanceId']: r for r in runs}
    resumed = bool(existing)
    failed_source = None
    for number, command in enumerate(cases(), 1):
        case = existing.get(command['title'])
        if case is None:
            case = admin.call(root + '/tests', command, key=f'case-{number}')
        else:
            # The list endpoint returns summaries, without currentRevision or steps.
            case = admin.call(root + '/tests/' + case['id'])
        place_demo_object(admin, root, 'TEST_PLAN', case['id'], plan_folder)
        instance = instances.get(case['id'])
        if instance is None:
            instance = admin.call(setroot + '/instances', {'testCaseId': case['id'],
                'testRevisionId': case['currentRevision']['id']}, key=f'instance-{number}')
        run = by_instance.get(instance['id'])
        if run is None:
            detail = admin.call(root + '/runs', {'testInstanceId': instance['id']}, key=f'run-{number}')
        else:
            detail = admin.call(root + '/runs/' + run['id'])
        attempt = detail['currentAttempt']
        runid = detail['run']['id']
        attemptroot = root + '/runs/' + runid + '/attempts/' + attempt['id']
        # The fourteenth run deliberately remains in progress with only its first step recorded.
        if attempt['status'] != 'FINISHED':
            for index, step in enumerate(list(attempt['steps'])):
                if number == 14 and index > 0:
                    break
                if step['conclusion'] != 'NOT_RUN':
                    continue
                conclusion = 'FAIL' if number in (12, 13) and index == 1 else 'PASS'
                actual = '符合预期' if number != 12 or index != 1 else '输入超量退货数量后仍可保存，未出现阻断提示。'
                attempt = admin.call(attemptroot + '/steps/' + step['stepKey'],
                    {'actualResult': actual, 'conclusion': conclusion, 'expectedVersion': step['rowVersion']},
                    method='PUT', key=f'step-{number}-{index}', version=step['rowVersion'])
            if number != 14:
                attempt = admin.call(attemptroot + '/finish', {'expectedVersion': attempt['rowVersion']},
                    key=f'finish-{number}', version=attempt['rowVersion'])
        if number == 12:
            failed_source = {'runId': runid, 'attemptId': attempt['id'], 'stepKey': attempt['steps'][1]['stepKey']}
    defects = admin.call(root + '/defects?limit=100')['items']
    title = 'A03-12 偏差：超量退货未被系统阻断'
    if not any(d['currentRevision']['title'] == title for d in defects):
        admin.call(root + '/defects/from-step', dict(failed_source, title=title,
            description='合成演示：退货数量超过原交货数量仍可保存，与 OQ 预期不符；来源冻结于 A03-12 的失败步骤。',
            severity='HIGH', priority='HIGH'), key='defect')
    print('Seed: existing data resumed/skipped' if resumed else 'Seed: A03 synthetic fixture created through business APIs')
    result = verify(admin, root, test_set['id'], sessions)
    if checkpoint is not None:
        checkpoint.write_text(json.dumps({'projectId': project['id'], 'setId': test_set['id']}), encoding='utf-8')
    return result


def verify(admin, root, setid, sessions):
    tests = admin.call(root + '/tests?limit=100')['items']
    instances = admin.call(root + '/test-sets/' + setid + '/instances/page?limit=100')['items']
    runs = admin.call(root + '/runs/page?testSetId=' + setid + '&limit=100')['items']
    defects = admin.call(root + '/defects?limit=100')['items']
    summary = admin.call(root + '/runs/summary?testSetId=' + setid)
    expected_titles = {command['title'] for command in cases()}
    if {test['title'] for test in tests} != expected_titles:
        raise RuntimeError('Demo test-case identifiers differ from the 14 expected A03 cases')
    step_counts = [len(admin.call(root + '/tests/' + test['id'])['currentRevision']['steps']) for test in tests]
    if any(count != 3 for count in step_counts):
        raise RuntimeError('Demo case steps differ from the expected three-step definitions')
    conclusions = {}
    contradiction = False
    for run in runs:
        attempt = admin.call(root + '/runs/' + run['id'])['currentAttempt']
        status = attempt['conclusion'] or attempt['status']
        conclusions[status] = conclusions.get(status, 0) + 1
        contradiction |= any(s['actualResult'] == '符合预期' and s['conclusion'] == 'FAIL' for s in attempt['steps'])
    if len(tests) != 14 or len(instances) != 14 or len(runs) != 14 or len(defects) != 1:
        raise RuntimeError('Demo object count mismatch; inspect the project instead of reseeding blindly')
    if conclusions != {'PASS': 11, 'FAIL': 2, 'RUNNING': 1} or not contradiction:
        raise RuntimeError('Demo run conclusions or independent actual-result sample missing')
    for api, role in sessions:
        access = api.call('/api/v1/me/permissions?projectId=' + root.rsplit('/', 1)[1])
        if role not in access['roles']:
            raise RuntimeError('Demo role verification failed')
        api.call(root + '/tests?limit=100')
    result = {'projectId': root.rsplit('/', 1)[1], 'project': 'jinsai 验证测试',
              'tests': len(tests), 'testSet': SET_NAME, 'instances': len(instances),
              'runs': conclusions, 'defects': len(defects), 'independentActualAndConclusion': contradiction,
              'stepsPerCase': sorted(set(step_counts)), 'summary': summary, 'threeRolesRead': True}
    print('API verification: ' + json.dumps(result, ensure_ascii=False))
    return result
