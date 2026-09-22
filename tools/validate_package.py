#!/usr/bin/env python3
"""Validate planning assets, not application implementation or product acceptance."""
from __future__ import annotations
import csv
import json
import re
import sys
from pathlib import Path
from decimal import Decimal
from calculate_budget import calculate
ROOT = Path(__file__).resolve().parents[1]

def read_csv(path: Path) -> list[dict]:
    with path.open(encoding='utf-8-sig', newline='') as handle:
        return list(csv.DictReader(handle))

def validate(root: Path = ROOT) -> dict:
    errors = []
    def check(ok: bool, message: str) -> None:
        if not ok:
            errors.append(message)
    required = ['README.md','docs/01-executive-project-plan.md','docs/02-technical-implementation.md',
                'docs/03-sources-and-assumptions.md','docs/04-github-publishing.md','contracts/domain-model.md',
                'contracts/openapi-core.json','planning/wbs.csv','planning/scope-matrix.csv',
                'planning/acceptance-cases.csv','planning/e2e-cases.csv','planning/sprints.csv',
                'planning/budget.json','tools/publish_github.py']
    for rel in required:
        check((root/rel).is_file(), f'缺少文件 {rel}')
    if errors:
        return {'ok':False,'errors':errors}
    modules=json.loads((root/'planning/module_catalogue.json').read_text(encoding='utf-8'))
    ids={m['id'] for m in modules}
    check(len(modules)==len(ids)==32,'模块编号重复或数量不是32')
    graph={m['id']:m['dependencies'] for m in modules}
    active, visited = set(), set()
    def visit(node: str) -> None:
        if node in active:
            raise ValueError(f'模块完整依赖存在环：{node}')
        if node in visited:
            return
        active.add(node)
        for dep in graph[node]:
            if dep not in ids:
                raise ValueError(f'未知模块依赖：{dep}')
            visit(dep)
        active.remove(node)
        visited.add(node)
    try:
        for node in graph:
            visit(node)
    except ValueError as exc:
        errors.append(str(exc))
    for m in modules:
        check((root/f"docs/modules/{m['id']}.md").is_file(), f"缺模块手册 {m['id']}")
        check(len(m['steps'])==6 and len(m['tests'])==5,f"模块分解数量异常 {m['id']}")
    tables={name:read_csv(root/f'planning/{name}.csv') for name in ('wbs','scope-matrix','acceptance-cases','e2e-cases','sprints','api-catalogue')}
    for name,n,key in [('wbs',192,'task_id'),('scope-matrix',192,'scope_id'),('acceptance-cases',160,'acceptance_id'),('e2e-cases',24,'case_id'),('sprints',78,'sprint')]:
        check(len(tables[name])==n,f'{name} 行数不是 {n}')
        check(len({x[key] for x in tables[name]})==len(tables[name]),f'{name} 编号重复')
    task_ids={x['task_id'] for x in tables['wbs']}
    ac_ids={x['acceptance_id'] for x in tables['acceptance-cases']}
    for row in tables['scope-matrix']:
        check(row['work_package'] in task_ids,'范围关联不存在的工作包')
        check(set(row['acceptance_ids'].split(';')) <= ac_ids,'范围关联不存在的验收')
    for i,row in enumerate(tables['sprints'],1):
        check(int(row['week_start'])==i*2-1 and int(row['week_end'])==i*2, f'迭代周数不连续 {i}')
    budget=calculate(json.loads((root/'planning/budget.json').read_text(encoding='utf-8')))
    # Baseline checks: deliberately fail when inputs change until docs are reconciled.
    check(budget['totals']['person_months']==Decimal('618'),'预算人月与V1.0文档不一致')
    check(budget['totals']['total']==Decimal('3354'),'预算总额与V1.0文档不一致；需同步修订计划书')
    api=json.loads((root/'contracts/openapi-core.json').read_text(encoding='utf-8'))
    check(api.get('openapi')=='3.1.0','OpenAPI版本异常')
    def walk(value):
        if isinstance(value,dict):
            if '$ref' in value and value['$ref'].startswith('#/'):
                target=api
                try:
                    for part in value['$ref'][2:].split('/'):
                        target=target[part.replace('~1','/').replace('~0','~')]
                except (KeyError,TypeError):
                    errors.append('失效OpenAPI引用 '+value['$ref'])
            for item in value.values():walk(item)
        elif isinstance(value,list):
            for item in value:walk(item)
    walk(api)
    operations=[]
    for path,methods in api['paths'].items():
        for verb,operation in methods.items():
            operations.append(operation['operationId'])
            check(set(re.findall(r'\{([^}]+)\}',path)) <= {p['name'] for p in operation.get('parameters',[]) if p['in']=='path'},'路径参数缺失 '+path)
    check(len(operations)==len(set(operations))==14,'核心API操作编号或数量异常')
    # Only standard markdown links in project-authored files. Dependency/build
    # directories can contain arbitrary third-party links and are not package
    # documentation owned by this validator.
    ignored_markdown_dirs = {'node_modules', 'target', 'dist', '__pycache__'}
    for md in root.rglob('*.md'):
        if ignored_markdown_dirs.intersection(md.relative_to(root).parts):
            continue
        for dest in re.findall(r'\]\(([^)]+)\)',md.read_text(encoding='utf-8')):
            if re.match(r'^[a-zA-Z]+:',dest) or dest.startswith('#'):
                continue
            target=dest.split('#')[0]
            if target:
                check((md.parent/target).exists(),f'失效本地链接 {md.relative_to(root)} -> {target}')
    not_run=sum(x['status']!='PASSED' for x in tables['acceptance-cases']+tables['e2e-cases'])
    return dict(ok=not errors, errors=errors, modules=len(modules), work_packages=len(tables['wbs']),
                module_acceptance_cases=len(tables['acceptance-cases']),e2e_cases=len(tables['e2e-cases']),
                sprints=len(tables['sprints']), api_catalogue_rows=len(tables['api-catalogue']),
                openapi_operations=len(operations), budget_total_wan=str(budget['totals']['total']),
                not_passed_product_cases=not_run,
                product_acceptance='NOT_EXECUTED',github_publication='NOT_PERFORMED_BY_THIS_VALIDATOR',
                note='只校验规划资产；不执行业务应用，不证明安全、性能、迁移或兼容通过。')

def main() -> int:
    try:
        result=validate()
    except (OSError,ValueError,KeyError) as exc:
        result={'ok':False,'errors':[str(exc)]}
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 0 if result['ok'] else 1

if __name__=='__main__':
    raise SystemExit(main())
