# R03-M03-002 B 段前端与契约记录

日期：2026-09-28
范围：项目访问最小闭环的前端面板、客户端边界和 API 契约；不包含后端 Java、数据库迁移或远端发布。

## 交付

- `apps/web/src/ProjectAccessPanel.tsx` 提供按需加载的租户/项目选择、成员列表、固定角色保存和撤权入口。
- `apps/web/src/projectAccess.ts` 为租户、项目、成员、角色、权限和统一错误响应提供运行时校验的 TypeScript 客户端。
- `contracts/project-access.json` 定义当前主体租户列表、项目创建/列表/详情/版本更新、成员候选/列表/授权/撤权、`GET /api/v1/me/permissions`；租户/域/初始成员 bootstrap 不暴露 HTTP 管理入口。
- `docs/adr/012-r03-project-access-slice.md` 和 README 记录同源 BFF 会话、固定角色以及未完成的 RLS/MFA/完整 M03 边界。

## 验证状态

| 检查 | 状态 | 证据 |
|---|---|---|
| 前端 Vitest | PASS | `npm run test:run -- --reporter=dot`；3 个文件、24 个测试通过（移除不在本轮范围的租户创建入口后重新执行） |
| 前端类型/构建 | PASS | `npm run build`；TypeScript/Vite 构建成功 |
| 前端 lint | PASS | `npm run lint`；保留既有 App effect warning，非阻断 |
| 后端接口和数据隔离 | NOT_RUN | 由 B 段后端任务负责，本记录不替代其集成证据 |
| 完整 M03 验收 | NOT_RUN | RLS、撤权传播、跨节点会话、MFA、完整审计仍未在本切片验证 |
