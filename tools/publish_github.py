#!/usr/bin/env python3
"""Preview, then explicitly create a NEW private personal GitHub repo and push this package.

Requires locally installed git and gh. Authentication remains in the user's GitHub CLI.
Default invocation is a dry run and performs no network operations or writes.
"""
from __future__ import annotations
import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ALLOWED_ROOTS={'README.md','SECURITY.md','NOTICE.md','.gitignore','docs','planning','contracts','tools','templates','examples','validation','deliverables'}

class PublishError(RuntimeError):
    pass

def validate_names(owner: str, repo: str) -> None:
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?',owner):
        raise PublishError('GitHub账号名称无效。')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}',repo) or repo.endswith('.git'):
        raise PublishError('仓库名称无效。')

def run(command: list[str], *, cwd: Path=ROOT, check: bool=True, timeout: int=90) -> subprocess.CompletedProcess:
    try:
        result=subprocess.run(command,cwd=str(cwd),text=True,encoding='utf-8',errors='replace',capture_output=True,timeout=timeout,shell=False)
    except (OSError,subprocess.TimeoutExpired) as exc:
        raise PublishError(f'无法完成 {command[0]} {command[1]}；请检查安装、网络或超时。') from exc
    if check and result.returncode:
        # Do not print raw diagnostics; a user-modified remote could contain credentials.
        raise PublishError(f'{command[0]} {command[1]} 失败（退出码 {result.returncode}）。请在本机检查授权、网络及Git状态；本工具不输出可能含秘密的原始诊断。')
    return result

def inspect_package(root: Path) -> None:
    if (root/'.git').exists():
        raise PublishError('目录已包含 .git；为防止影响现有历史，本脚本拒绝继续。参见发布手册的中断恢复。')
    if not (root/'planning/module_catalogue.json').is_file() or not (root/'README.md').is_file():
        raise PublishError('不是完整Test365Alm规划包。')
    extras={p.name for p in root.iterdir()}-ALLOWED_ROOTS
    if extras:
        raise PublishError('发现未批准根目录项目，请先人工检查：'+', '.join(sorted(extras)))
    for path in root.rglob('*'):
        if path.is_symlink():
            raise PublishError('禁止发布符号链接，请先人工确认。')
        if path.is_file():
            if path.suffix.lower() in {'.pem','.key','.p12','.pfx'} or path.name=='.env':
                raise PublishError('发现疑似秘密文件；停止发布。')
            if path.stat().st_size>50*1024*1024:
                raise PublishError('存在超过50MiB的文件；请先审查存储方案。')
            if path.suffix.lower() in {'.md','.json','.csv','.py','.txt','.yaml','.yml'}:
                content=path.read_text(encoding='utf-8-sig')
                if re.search(r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)',content):
                    raise PublishError('发现疑似访问令牌或私钥，停止发布。请轮换真实泄漏凭据。')

def publish(owner: str, repo: str, execute: bool=False, root: Path=ROOT) -> dict:
    validate_names(owner,repo)
    full=f'{owner}/{repo}'
    if not execute:
        return {'mode':'DRY_RUN','target':full,'visibility':'private','network_calls':0,
                'plan':['校验文件和预算','检查本机git/gh及已登录个人账号','确认目标仓库不可访问且返回404','建立本包main初始提交','创建新的私有仓库并推送','对比本地与远端main提交'],
                'next':'审查文件后显式加 --execute。需要Git、GitHub CLI及合法账号权限。'}
    inspect_package(root)
    for tool in ('git','gh'):
        if not shutil.which(tool):
            raise PublishError(f'本机没有安装 {tool}。先安装工具，再运行；不要在聊天中提供令牌。')
    check=run([sys.executable,str(root/'tools/validate_package.py')],cwd=root)
    run(['gh','auth','status','--hostname','github.com'],cwd=root)
    profile=json.loads(run(['gh','api','user'],cwd=root).stdout)
    if str(profile.get('login','')).casefold()!=owner.casefold():
        raise PublishError('已登录账号不是指定owner。本工具只为当前个人账号新建私有仓库，不代替组织授权。')
    target=run(['gh','api',f'repos/{full}'],cwd=root,check=False)
    if target.returncode==0:
        raise PublishError('目标仓库已存在；本工具不会覆盖或修改。请先查看远端再按手册人工决定。')
    if 'HTTP 404' not in target.stderr:
        raise PublishError('不能确认目标返回404；可能是网络、权限或限流，已停止。')
    run(['git','init','-b','main'],cwd=root)
    run(['git','add','--','.'],cwd=root)
    # Per-command identity only. This is an explicit automation label, not a forged user identity.
    run(['git','-c','user.name=Test365Alm Bootstrap','-c','user.email=test365alm-bootstrap@users.noreply.github.com','commit','-m','docs: add Test365Alm project plan and implementation baseline'],cwd=root)
    local=run(['git','rev-parse','HEAD'],cwd=root).stdout.strip()
    run(['gh','repo','create',full,'--private','--source',str(root),'--remote','origin','--push','--description','Test365Alm project plan, detailed implementation guide and planning assets'],cwd=root,timeout=300)
    metadata=json.loads(run(['gh','api',f'repos/{full}'],cwd=root).stdout)
    if metadata.get('private') is not True:
        raise PublishError('远端可见性验证异常；未自动修改，请立即在GitHub检查。')
    remote=run(['gh','api',f'repos/{full}/commits/main','--jq','.sha'],cwd=root).stdout.strip()
    if local!=remote:
        raise PublishError('远端提交与本地不一致；请检查推送状态。不会强制推送。')
    return {'mode':'PUBLISHED','repository':metadata['html_url'],'visibility':'private','branch':'main','commit':local,
            'note':'仅文档与辅助工具；不代表业务应用完成。新仓库接入ChatGPT可能还需调整GitHub App的仓库访问授权。'}

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner',default='fangzhiy')
    parser.add_argument('--repo',default='Test365Alm')
    parser.add_argument('--execute',action='store_true',help='Explicitly create a private repository and push. Default is preview only.')
    args=parser.parse_args()
    try:
        result=publish(args.owner,args.repo,args.execute)
    except (PublishError,ValueError,KeyError) as exc:
        print(f'发布未完成：{exc}',file=sys.stderr)
        print('未执行删除或强制推送。如在创建/推送时中断，远端可能已创建；按发布手册核对后再操作。',file=sys.stderr)
        return 1
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 0

if __name__=='__main__':
    raise SystemExit(main())
