# 资料来源与事实边界

研究日期：2026-09-20。公开网页可能更新；项目启动时应在合法范围内登记版本和核验日期。以下引用用于产品范围或公开技术能力，不能证明 Test365Alm 已实现对应功能。

| 编号 | 一手资料 | 本项目使用范围 |
|---|---|---|
| R01 | [OpenText AQM Editions and Cloud Offerings](https://admhelp.microfocus.com/documents/alm/alm-editions/ALM-Editions.htm) | Edition、扩展、云服务与原生功能的边界；页面声明反映最新发行状态，不能当成未经核验的 26.1 固定历史快照 |
| R02 | [AQM 26.1 Develop / API references](https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/api_guides_main_page.htm) | 项目 REST、站点管理 REST、OTA/COM、站点管理 COM、自定义测试类型等不同兼容面 |
| R03 | [AQM 26.1 Libraries, Baselines and Pinned Tests](https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/UG/c_libraries_overview.htm) | 基线固定资产和关系版本及库外依赖；固定/清除测试集基线的历史行为 |
| R04 | [AQM 26.1 Workflow Script Editor](https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/WF_Customization/wf_script_create.htm) | VBScript、模板和项目事件调用方式；不等同于新平台规则实现 |
| R05 | [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) | 检索时页面标示 4.1.1；Java 21 位于其公布兼容范围。最终项目版本需测试后锁定 |
| R06 | [Node.js releases](https://nodejs.org/en/about/previous-releases) | 检索时 Node 24 标示 LTS；不选已结束支持的运行时作为新项目基线 |
| R07 | [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/) | 支持周期与补丁维护；PostgreSQL 17 作为候选主版本，不承诺未来补丁时间 |
| R08 | [PostgreSQL 17 Row Security Policies](https://www.postgresql.org/docs/17/ddl-rowsecurity.html) | RLS、默认拒绝与 owner/superuser/BYPASSRLS 例外；权限体系仍需应用校验 |
| R09 | [Keycloak server administration](https://www.keycloak.org/docs/latest/server_admin/index.html) | OIDC/SAML、身份代理、角色、会话与 LDAP 等候选身份能力；实际配置另验 |
| R10 | [AQM 26.1 Editions and lifecycle](https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/UG/editions_lifecycle.htm) | 发布、需求、测试计划、实验室、缺陷及报表的业务主线 |
| R11 | [AQM 26.1 BPT test-plan actions](https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/BPT/ui_tpmenubuttons.htm) | BPT 组件、流程及 Packaged Apps Kit 相关能力需单列核验 |
| R12 | [GitHub CLI gh repo create](https://cli.github.com/manual/gh_repo_create)；[gh auth login](https://cli.github.com/manual/gh_auth_login) | 本地发布脚本的创建私有仓库、认证与推送命令 |
| R13 | [OpenAPI Specification 3.1.0](https://spec.openapis.org/oas/v3.1.0) | 原生接口契约示例格式；示例不是旧 ALM API 的兼容实现 |
| R14 | [.NET releases and support](https://learn.microsoft.com/en-us/dotnet/core/releases-and-support) | Windows 桥接候选 .NET 10 LTS；COM 细节和目标系统需专项验证 |

## 明确不作的声明

不宣称已拥有客户真实 ALM 数据或 SDK；不宣称 26.1 是用户确认版本；不宣称所有旧客户端、脚本和插件可零修改运行；不宣称已通过性能、安全、法规或第三方认证；不宣称本文件包已经完成软件开发。

32 个模块、192 个工作包和初始验收用例是本项目的工程分解，不是原厂的官方功能数量。P0 必须把官方手册、扩展和实机行为映射进完整的签批范围；新增细项应保留父级来源，并接受变更治理。

## GitHub 操作状态

本次已通过连接器确认账号为 fangzhiy，并读取可访问仓库。访问 fangzhiy/Test365Alm 返回 404；这表示本次连接未能访问该仓库，不证明 GitHub 全局不存在任何同名资源。当前连接器未暴露新建仓库动作，因此本次没有在 GitHub 创建仓库，也没有向其他仓库写入或公开项目资料。本文件包提供本地发布工具，供已登录并获授权的用户执行；其未在本次会话完成远端发布测试。

补充技术来源：R15 [Vite Getting Started](https://vite.dev/guide/)；R16 [Docker Compose Quickstart](https://docs.docker.com/compose/gettingstarted/)。仅用于脚手架及开发环境命令形式；未在本次会话安装或运行实际业务应用。
