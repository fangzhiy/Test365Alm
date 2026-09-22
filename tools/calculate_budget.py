#!/usr/bin/env python3
"""Recalculate the hypothetical budget; values are in RMB 10,000 units."""
from __future__ import annotations
import argparse
import json
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def calculate(data: dict, cost: Decimal | None = None) -> dict:
    cost = cost if cost is not None else Decimal(data['person_month_cost_wan'])
    reserve = Decimal(data['contingency_rate'])
    if cost <= 0 or reserve < 0:
        raise ValueError('人月成本须大于0；预备费比例不得为负。')
    rows = []
    cumulative = Decimal('0')
    for phase in data['phases']:
        months, fte = Decimal(phase['months']), Decimal(phase['fte'])
        environment, external = Decimal(phase['environment_wan']), Decimal(phase['external_wan'])
        if months <= 0 or fte <= 0 or environment < 0 or external < 0:
            raise ValueError('阶段月份/人数须正数，费用不得负数。')
        person_months = months * fte
        labor = person_months * cost
        subtotal = labor + environment + external
        contingency = subtotal * reserve
        total = subtotal + contingency
        cumulative += total
        rows.append(dict(phase=phase['id'], person_months=person_months, labor=labor,
                         environment=environment, external=external, subtotal=subtotal,
                         contingency=contingency, total=total, cumulative=cumulative))
    fields = ('person_months', 'labor', 'environment', 'external', 'subtotal', 'contingency', 'total')
    return dict(status='ASSUMPTIONS_NOT_APPROVED', unit='万元人民币', phases=rows,
                totals={key: sum((row[key] for row in rows), Decimal('0')) for key in fields})

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cost-wan', type=Decimal, help='Override hypothetical person-month cost.')
    parser.add_argument('--json', action='store_true', help='Print machine-readable result.')
    args = parser.parse_args()
    try:
        result = calculate(json.loads((ROOT / 'planning/budget.json').read_text(encoding='utf-8')), args.cost_wan)
    except (ValueError, OSError, KeyError) as exc:
        parser.error(str(exc))
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
    else:
        print('规划假设，尚未审批；单位：万元人民币')
        print('阶段   人月     人工      环境    外部     预备费     合计       累计')
        for x in result['phases']:
            print(f"{x['phase']:4} {x['person_months']:7.1f} {x['labor']:9.1f} {x['environment']:7.1f} {x['external']:7.1f} {x['contingency']:9.1f} {x['total']:10.1f} {x['cumulative']:10.1f}")
        print('总人月:', result['totals']['person_months'], '总规划投入:', result['totals']['total'])
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
