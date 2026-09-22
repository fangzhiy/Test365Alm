# 创建私有 GitHub 仓库与上传手册

## 本次真实状态

已通过GitHub连接器确认账号fangzhiy并查询目标；目标仓库返回404，当前连接器未提供新建仓库动作。因此本次没有创建Test365Alm，没有上传任何文件，也没有修改其他仓库。404仅代表本次连接不可访问，不能证明全局不存在。

发布工具是供你在自己电脑上运行的替代路径，不是已经执行的远端结果。下面的创建命令依据GitHub CLI官方文档[R12]；脚本离线测试不等于真实网络发布测试。

## 1. 准备

解压完整文件包，进入最外层Test365Alm目录。安装Git、Python 3.10+和GitHub CLI；Windows推荐使用已批准的软件安装渠道。重新打开终端，分别执行 `git --version`、`python --version`、`gh --version`，确认可用。

仓库包含内部预算及研发设计，默认创建私有仓库。不需要把密码、PAT或验证码发送到聊天中。

## 2. 本地校验与登录

```powershell
cd Test365Alm
python tools/validate_package.py
python tools/calculate_budget.py
python -m unittest discover -s tools/tests -v
gh auth login --hostname github.com
gh auth status --hostname github.com
```

按GitHub CLI的浏览器授权提示完成登录。若使用组织账号或公司GitHub Enterprise，本脚本默认个人github.com路径不适用，应由管理员审查修改；不能擅自套用个人创建权限。

## 3. 预览并创建

```powershell
python tools/publish_github.py --owner fangzhiy --repo Test365Alm
python tools/publish_github.py --owner fangzhiy --repo Test365Alm --execute
```

第一条只预览，不访问网络、不写Git。第二条会核验包、核对当前登录人、查询目标，然后在全新目录建立main初始提交，调用gh创建私有仓库并推送。成功后核验private和远端提交哈希，并输出真实仓库地址。

工具不会覆盖已有仓库，不会强制推送，不修改全局Git身份，不更改其他项目，不自动创建大量Issue。初始提交作者标识为Test365Alm Bootstrap，明确是自动化提交标签而不是冒用个人身份。

## 4. 发布后核验

```powershell
gh repo view fangzhiy/Test365Alm --json nameWithOwner,visibility,url,defaultBranchRef
git log -1 --oneline
git status --short
```

打开返回的实际地址，检查README、两份Word、docs、planning、contracts及tools。需要让ChatGPT后续访问时，按GitHub App安装的实际范围将新仓库纳入已授权列表；本脚本不能替你修改App管理权限。

## 5. 中断与已有仓库处理

若提示已有.git或已有目标仓库，停止重复执行--execute。先用 `gh repo view fangzhiy/Test365Alm`、`git remote -v`、`git status` 和 `git log -1` 核查。不要运行任何删除、清空或force push命令。

若创建成功但推送失败，确认远端属于你的新建项目、确实没有他人提交、origin正确后，可由你执行普通 `git push -u origin main`；如果有其他历史，先拉取和评审，使用普通PR合入而非覆盖。本包不自动决定冲突解决。

若工具仍不可访问目标或返回限流/权限错误，应在本机解决认证或网络，不提供绕过权限方案，也不要把令牌贴入仓库。

## 6. 研发工作管理

192个工作包和78个迭代保存在planning，不代表已经创建远端Issues/Milestones。项目负责人在P0估算、明确实际负责人后，按templates/issue.md逐批建立Issue并关联范围及测试。分支保护、审查人、组织规则和CI均需按公司权限实际配置并核验，不能只写进文档就宣称生效。
