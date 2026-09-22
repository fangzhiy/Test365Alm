#!/usr/bin/env python3
"""Check the P0 planning package and design contracts without running an app.

The check is intentionally read-only and standard-library-only.  A healthy
result means that the planning inputs and design contracts are present and
structurally readable; it never means that the ALM application is implemented
or that a product acceptance run has passed.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[1]

# These are the package inputs needed by the P0 planning and contract checks.
# They deliberately exclude docs, validation output, p0 evidence, and any
# future application source: this utility must not turn those artifacts into a
# product acceptance claim.
PLANNING_ASSETS = (
    "planning/module_catalogue.json",
    "planning/wbs.csv",
    "planning/scope-matrix.csv",
    "planning/acceptance-cases.csv",
    "planning/e2e-cases.csv",
    "planning/sprints.csv",
    "planning/api-catalogue.csv",
    "planning/budget.json",
)
CONTRACT_ASSETS = (
    "contracts/domain-model.md",
    "contracts/openapi-core.json",
    "contracts/runner-protocol.md",
)
TOOL_ASSETS = (
    "tools/calculate_budget.py",
    "tools/validate_package.py",
    "tools/publish_github.py",
)
P0_TEMPLATE_ASSETS = (
    "p0/README.md",
    "p0/environment-ledger.csv",
    "p0/scope-baseline.md",
    "p0/golden-dataset-manifest.json",
    "p0/migration-gap-report.md",
    "p0/glossary.md",
    "p0/threat-model.md",
)


def _missing(root: Path, paths: Iterable[str]) -> list[str]:
    return [relative for relative in paths if not (root / relative).is_file()]


def _read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def _read_csv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        return list(reader.fieldnames or []), list(reader)


def _asset_report(root: Path, paths: Iterable[str], errors: list[str]) -> dict[str, Any]:
    paths = tuple(paths)
    missing = _missing(root, paths)
    for relative in missing:
        errors.append(f"缺少文件 {relative}")
    return {
        "ok": not missing,
        "required_assets": list(paths),
        "checked_assets": [relative for relative in paths if relative not in missing],
        "errors": errors,
    }


def _check_planning(root: Path) -> dict[str, Any]:
    errors: list[str] = []
    report = _asset_report(root, PLANNING_ASSETS + TOOL_ASSETS, errors)
    if not report["ok"]:
        report["status"] = "BLOCKED"
        return report

    try:
        modules = _read_json(root / "planning/module_catalogue.json")
        if not isinstance(modules, list) or len(modules) != 32:
            errors.append("planning/module_catalogue.json 不是包含32个模块的数组")
        elif any(not isinstance(item, dict) or not item.get("id") for item in modules):
            errors.append("planning/module_catalogue.json 存在缺少id的模块")

        budget = _read_json(root / "planning/budget.json")
        if not isinstance(budget, dict) or not budget.get("phases"):
            errors.append("planning/budget.json 缺少phases预算输入")

        csv_requirements = {
            "planning/wbs.csv": "task_id",
            "planning/scope-matrix.csv": "scope_id",
            "planning/acceptance-cases.csv": "acceptance_id",
            "planning/e2e-cases.csv": "case_id",
            "planning/sprints.csv": "sprint",
            "planning/api-catalogue.csv": "api_id",
        }
        for relative, key in csv_requirements.items():
            headers, rows = _read_csv(root / relative)
            if key not in headers:
                errors.append(f"{relative} 缺少列 {key}")
            if not rows:
                errors.append(f"{relative} 没有数据行")
    except (OSError, ValueError, TypeError, json.JSONDecodeError, csv.Error) as exc:
        errors.append(f"规划资产不可读取：{exc}")

    report["ok"] = not errors
    report["errors"] = errors
    report["status"] = "PASS" if report["ok"] else "BLOCKED"
    return report


def _check_contracts(root: Path) -> dict[str, Any]:
    errors: list[str] = []
    report = _asset_report(root, CONTRACT_ASSETS, errors)
    if not report["ok"]:
        report["status"] = "BLOCKED"
        return report

    openapi: dict[str, Any] | None = None
    try:
        for relative in ("contracts/domain-model.md", "contracts/runner-protocol.md"):
            if not (root / relative).read_text(encoding="utf-8").strip():
                errors.append(f"{relative} 为空")

        value = _read_json(root / "contracts/openapi-core.json")
        if not isinstance(value, dict):
            errors.append("contracts/openapi-core.json 顶层必须是对象")
        else:
            openapi = value
            if value.get("openapi") != "3.1.0":
                errors.append("contracts/openapi-core.json OpenAPI版本不是3.1.0")
            paths = value.get("paths")
            if not isinstance(paths, dict) or not paths:
                errors.append("contracts/openapi-core.json 缺少paths")
            else:
                operations: list[str] = []
                for path, methods in paths.items():
                    if not isinstance(methods, dict):
                        errors.append(f"OpenAPI路径定义无效 {path}")
                        continue
                    for verb, operation in methods.items():
                        if verb.lower() not in {"get", "put", "post", "delete", "options", "head", "patch", "trace"}:
                            continue
                        if not isinstance(operation, dict) or not operation.get("operationId"):
                            errors.append(f"OpenAPI操作缺少operationId {verb.upper()} {path}")
                        else:
                            operations.append(str(operation["operationId"]))
                if len(operations) != len(set(operations)):
                    errors.append("OpenAPI operationId 重复")
                report["openapi_operations"] = len(operations)
    except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
        errors.append(f"契约不可读取：{exc}")

    # Check local JSON references when the contract contains them.  A broken
    # local reference is a contract defect, while external references remain
    # intentionally unresolved by this offline tool.
    if openapi is not None:
        def walk(value: Any) -> None:
            if isinstance(value, dict):
                reference = value.get("$ref")
                if isinstance(reference, str) and reference.startswith("#/"):
                    target: Any = openapi
                    try:
                        for part in reference[2:].split("/"):
                            target = target[part.replace("~1", "/").replace("~0", "~")]
                    except (KeyError, TypeError):
                        errors.append(f"失效OpenAPI引用 {reference}")
                for item in value.values():
                    walk(item)
            elif isinstance(value, list):
                for item in value:
                    walk(item)

        walk(openapi)

    report["ok"] = not errors
    report["errors"] = errors
    report["status"] = "PASS" if report["ok"] else "BLOCKED"
    return report


def _check_p0_inputs(root: Path) -> dict[str, Any]:
    """Report P0 evidence blockers separately from planning asset health.

    The templates are useful evidence indexes, but their presence must not be
    treated as evidence that a target ALM environment or customer data exists.
    This report therefore remains ``INPUTS_MISSING`` for the initial package,
    while the package and contract checks can still be healthy.
    """
    missing_templates = _missing(root, P0_TEMPLATE_ASSETS)
    blockers: list[dict[str, str]] = []

    def add(blocker_id: str, input_name: str, reason: str, evidence: str) -> None:
        blockers.append({"id": blocker_id, "input": input_name, "reason": reason, "evidence": evidence})

    ledger_path = root / "p0/environment-ledger.csv"
    ledger: dict[str, dict[str, str]] = {}
    if ledger_path.is_file():
        try:
            _, rows = _read_csv(ledger_path)
            ledger = {row.get("record_id", ""): row for row in rows}
        except (OSError, csv.Error):
            # A missing/invalid ledger is itself enough to keep each dependent
            # decision open; retain a concise machine-readable reason below.
            ledger = {}

    unresolved = lambda key: not ledger or ledger.get(key, {}).get("status") != "CLOSED" or ledger.get(key, {}).get("current_value") in {"", "UNCONFIRMED"}
    if unresolved("D01") or unresolved("D02") or unresolved("D03"):
        add("B01", "target_version_edition_extensions_authorization", "目标版本、Edition、扩展或授权未确认", "p0/environment-ledger.csv")
    if unresolved("D05"):
        add("B02", "authorized_deidentified_samples", "三类脱敏样本、访问审批和哈希未取得", "p0/golden-dataset-manifest.json")
    if unresolved("ENV-03"):
        add("B03", "windows_vm_sdk_legacy_clients", "Windows VM、位数、SDK 或旧客户端授权未确认", "p0/environment-ledger.csv")
    if unresolved("D08"):
        add("B04", "compatibility_license_distribution", "REST/OTA/COM/VBScript 许可或分发路径未确认", "p0/environment-ledger.csv")
    if unresolved("D07"):
        add("B05", "roles_and_p0_budget", "关键角色或 P0 预算尚未确认", "p0/environment-ledger.csv")

    manifest_path = root / "p0/golden-dataset-manifest.json"
    if manifest_path.is_file():
        try:
            manifest = _read_json(manifest_path)
            samples = manifest.get("samples", []) if isinstance(manifest, dict) else []
            if not isinstance(manifest, dict) or manifest.get("status") == "MISSING_INPUT" or not manifest.get("authorized") or any(item.get("status") != "READY" for item in samples if isinstance(item, dict)):
                # B02 is already derived from the ledger when available; do
                # not duplicate it when a complete ledger is later supplied.
                if not any(item["id"] == "B02" for item in blockers):
                    add("B02", "authorized_deidentified_samples", "黄金数据清单仍标记为待输入", "p0/golden-dataset-manifest.json")
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            if not any(item["id"] == "B02" for item in blockers):
                add("B02", "authorized_deidentified_samples", "黄金数据清单不可读取", "p0/golden-dataset-manifest.json")

    # No application build/lock evidence is present in the planning package;
    # record this as a P0 input blocker without probing or starting an app.
    if not (root / "apps").is_dir():
        add("B08", "reproducible_build_and_dependency_lock", "候选依赖锁和空机复现证据尚未归档", "apps/ 与构建证据")

    status = "READY" if not missing_templates and not blockers else "INPUTS_MISSING"
    return {
        "ok": not missing_templates and not blockers,
        "status": status,
        "required_templates": list(P0_TEMPLATE_ASSETS),
        "templates_missing": missing_templates,
        "inputs_missing": sorted(set(missing_templates + [item["id"] for item in blockers])),
        "blockers": blockers,
        "note": "模板状态和阻塞项是P0证据入口；没有真实环境、授权样本或复跑证据时不能关闭。",
    }


def check_health(root: Path = ROOT) -> dict[str, Any]:
    """Return a JSON-serializable, read-only health report for package assets."""
    root = Path(root).resolve()
    package_check = _check_planning(root)
    contract_check = _check_contracts(root)
    p0_input_check = _check_p0_inputs(root)
    asset_missing = _missing(root, PLANNING_ASSETS + CONTRACT_ASSETS + TOOL_ASSETS)
    inputs_missing = sorted(set(asset_missing + p0_input_check["inputs_missing"]))
    assets_ok = bool(package_check["ok"] and contract_check["ok"] and not asset_missing)
    return {
        "tool": "p0_health_check",
        "schema_version": "1",
        "ok": assets_ok,
        "status": "PLANNING_ASSETS_HEALTHY" if assets_ok else "PLANNING_ASSETS_BLOCKED",
        "package_check": package_check,
        "contract_check": contract_check,
        "p0_input_check": p0_input_check,
        "input_status": p0_input_check["status"],
        "inputs_missing": inputs_missing,
        "planning_only": True,
        "business_acceptance": "NOT_EXECUTED",
        "product_acceptance": "NOT_EXECUTED",
        "application_status": "NOT_IMPLEMENTED",
        "note": "只检查planning、contracts和tools资产；不启动业务应用，不证明业务、迁移、兼容、安全或性能通过。",
    }


# Keep a familiar name for callers that use the existing validate_package API.
validate = check_health


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Test365Alm package root")
    args = parser.parse_args(argv)
    try:
        result = check_health(args.root)
    except (OSError, ValueError, TypeError) as exc:
        result = {
            "tool": "p0_health_check",
            "schema_version": "1",
            "ok": False,
            "status": "PLANNING_ASSETS_BLOCKED",
            "package_check": {"ok": False, "errors": [str(exc)]},
            "contract_check": {"ok": False, "errors": [str(exc)]},
            "p0_input_check": {"ok": False, "status": "INPUTS_MISSING", "inputs_missing": []},
            "input_status": "INPUTS_MISSING",
            "inputs_missing": [],
            "planning_only": True,
            "business_acceptance": "NOT_EXECUTED",
            "product_acceptance": "NOT_EXECUTED",
            "application_status": "NOT_IMPLEMENTED",
            "note": "只检查planning、contracts和tools资产；不启动业务应用，不证明业务、迁移、兼容、安全或性能通过。",
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
