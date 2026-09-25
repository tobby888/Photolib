# 网站问题反馈模块 spec

## Problem Statement

系统目前没有成员主动向管理员反馈「网站问题 / 改进建议」的站内通道：站外访客只能靠页脚配置的群/邮箱联系，登录成员想报障也没有闭环入口。现有的「消息中心」只是单向通知（系统事件 + 管理员/部长主动发消息），没有回复、没有状态跟踪、没有成员提交反馈的能力。目标：让「消息中心」承担网站问题反馈功能。

## Solution

在消息中心内新增「反馈」标签：任何能进系统的登录成员可提交反馈（标题 + 分类 + 正文，正文可贴图）；`ADMIN` 在消息中心的「反馈」标签里查看全部反馈、按状态筛选、改状态、回复；提交人能看到自己每条反馈的「工单线程」（正文 + 回复 + 状态变更）。复用现有站内 + 企业微信投递链路，不新增权限码。

## Decisions（grilling 记录）

| # | 决策 | 结论 |
|---|------|------|
| Q1 | 「承担」的边界 | 消息中心就是反馈的收发箱，查看与回复都落在这里 |
| Q2 | 谁能提交 | 仅登录成员（任何能进系统的账号），站外访客不在本期 |
| Q3 | 交互深度 | 轻量工单：可回复 + 三态状态 |
| Q4 | 接收与处理方 | 仅 ADMIN（`hasRole('ADMIN')`，不新增权限码） |
| Q5 | 反馈内容范围 | 问题 / 建议两类，标题 + 正文（RichTextEditor，支持贴图） |
| Q6 | 提交入口 | 消息中心内「我要反馈」按钮 + 页脚「问题反馈」链接 |
| Q7 | 呈现形态 | 独立「反馈」标签（仅 ADMIN 全量 + 状态筛选；新反馈给 ADMIN 点未读通知） |
| Q8 | 提交人视角 | 工单线程：标题 + 状态 + 正文与回复/状态变更按时间串起来 |
| Q9 | 状态机 | 提交即「待处理」；ADMIN 手动改「处理中」「已解决」，可重开回处理中；状态变更通知提交人 |
| Q10 | 多轮对话 | 双方可多轮回复，不指派；ADMIN 回复→通知提交人，提交人追加→通知所有 ADMIN |
| Q11 | 推送范围 | 新反馈→所有 ADMIN；回复/状态变更→提交人；沿用「绑企微才外发，否则只站内」 |
| Q12 | 反垃圾/限流 | 轻量限流：每人每分钟 1 条、每天 20 条 |
| Q13 | 生命周期 | 不删除/不撤回；「已解决」终态、可重开；不自动归档 |
| Q14 | 归属与可见性 | 实名：提交人身份对 ADMIN 可见，普通成员只看自己的反馈 |

## User Stories

成员侧（任何已登录、能进系统的账号）：

1. As a 登录成员, I want to submit feedback with a title, category (问题/建议) and body (可贴图), so that I can report a website issue or suggest an improvement.
2. As a 登录成员, I want to see the list of feedback I submitted, each with its current status, so that I know what is still open.
3. As a 登录成员, I want to open one of my feedback as a thread (body + replies + status changes in time order), so that I can follow the whole conversation.
4. As a 登录成员, I want to append a reply to my own feedback, so that I can add details after submitting.
5. As a 登录成员, I want to receive an in-app (and 企业微信, if bound) notification when an admin replies or changes status, so that I don't miss progress.

管理侧（持有 `ADMIN` 角色）：

6. As an admin, I want to see all feedback in the message center's 「反馈」tab, filterable by status, so that I can triage reports.
7. As an admin, I want to open a feedback thread and see the submitter's identity, so that I know who reported it.
8. As an admin, I want to reply to a feedback, so that the submitter gets an answer.
9. As an admin, I want to change a feedback's status (待处理→处理中→已解决, and reopen), so that the submitter sees progress.
10. As an admin, I want to receive an in-app (and 企业微信, if bound) notification when new feedback arrives, so that I don't miss reports.

权限边界：

11. As a non-admin member, I should only see my own feedback, never other members' feedback.
12. As a user without the `ADMIN` role, I should not be able to list all feedback or change status.
13. As an anonymous (unauthenticated) visitor, I should have no access to feedback submission at all.

审计：

14. As the system, I record write operations (submit / reply / status change) in the audit log, so that changes are traceable.

## Implementation Decisions

- **独立模块**：新增 `feedback` 域（Controller/Service/Mapper/Entity），不复用也不扩展 `notification` 域——反馈是「一份内容 + 若干处理人 + 有状态」的共享工单，与 `user_notification` 的「按收件人各存一行」单向广播模型不是一回事。
- **数据模型（三张表，新 Flyway V56）**：
  - `feedback`：工单头。`id`、`submitter_id`（→ app_user）、`title`、`content`（纯文本快照）、`content_html`、`category`（`ISSUE`/`SUGGESTION`）、`status`（`PENDING`/`IN_PROGRESS`/`RESOLVED`）、`created_at`、`updated_at`、`version`（乐观锁）。
  - `feedback_reply`：对话。`id`、`feedback_id`、`author_id`（提交人或 ADMIN）、`content`、`content_html`、`created_at`。追加型，不可编辑。
  - `feedback_status_change`：状态流转。`id`、`feedback_id`、`from_status`、`to_status`、`operator_id`、`created_at`。既供提交人时间线展示，也供审计/追溯。
- **状态机**：提交即 `PENDING`；`ADMIN` 改为 `IN_PROGRESS`/`RESOLVED`，`RESOLVED` 可重开回 `IN_PROGRESS`；每次变更写一条 `feedback_status_change`。
- **通知事件（复用 `NotificationService` 的站内 + 企微投递）**：`FEEDBACK_CREATED`→所有 ADMIN；`FEEDBACK_REPLIED`→提交人（ADMIN 回复）；`FEEDBACK_UPDATED`→所有 ADMIN（提交人追加）；`FEEDBACK_STATUS_CHANGED`→提交人。企微外发沿用「绑定了 `wecom_userid` 才外发，否则只站内」的既有规则。
  - 注意：`notifyUser` 的 `actionUrl` 按事件前缀推导（`REQUEST_*`→`/requests` 等），对 `FEEDBACK_*` 推导不出带 id 的跳转。反馈通知需仿照 `sendMessage` 的方式直接构造 `UserNotificationEntity` 并写入具体 `actionUrl = /notifications/feedback/{feedbackId}`（或为 `notifyUser` 增加可显式传 `actionUrl` 的重载）。
- **消息中心 UI**：`NotificationsPage` 加「消息 / 反馈」标签切换。「反馈」标签：ADMIN 看全量 + 按状态筛选；非 ADMIN 只看「我提交的反馈」。线程页新增路由 `/notifications/feedback/:feedbackId`（正文 + 回复 + 状态变更时间线）。
- **提交入口**：消息中心页加「我要反馈」按钮（对所有能进系统的成员可见）；同时在页脚配置里加一条「问题反馈」链接指向它（页脚链接现有后端白名单校验，`#/` 开头的站内 hash 路由走站内跳转）。
- **正文与图片**：正文复用 `RichTextEditor`，贴图复用现有 `message_image`（对话参与者可见），不新增附件上传。
- **权限**：提交/看自己/回复自己的反馈 = 任何已登录、能进系统的账号；全量列表 / 改状态 = `hasRole('ADMIN')`。**不新增 `PermissionCode`**，因此无需动权限组种子与 `StepUpPermissionCoverageTests`。
- **限流**：每人每分钟 1 条、每天 20 条（简单计数实现，不进风控体系）。
- **生命周期**：不提供删除/撤回；`RESOLVED` 为终态、ADMIN 可重开；量小不自动归档。
- **实名**：提交人身份对 ADMIN 可见（`submitter_id` 关联），普通成员仅能查询自己的反馈。
- **贴图鉴权（落地补充）**：为让普通成员能在反馈里贴图，`POST /notifications/images` 的上传门从 `MESSAGE_SEND` 放宽到 `isAuthenticated()`；读侧 `MessageImageAuthorizationService` 增加「该图片被引用在调用者提交的反馈（含其回复）里即可读」的判定。图片可读性最终由「上传人 / ADMIN / 投递对象 / 反馈提交人」四个条件共同决定。`page_url` 字段未实现——提交入口只在消息中心，自动采集到的恒为消息中心本身，价值不足，故去掉。
- **审计**：写操作自动进审计（复用 `AuditInterceptor`），确认资源类型 / 资源 ID / 请求 ID / 详情被捕获。
- **API**：`POST /api/v1/feedback`（提交）、`GET /api/v1/feedback`（ADMIN 全量 / 非 ADMIN 仅自己，支持 `status` 筛选）、`GET /api/v1/feedback/{id}`（线程 + 状态变更，ADMIN 或提交人可见）、`POST /api/v1/feedback/{id}/reply`（提交人或 ADMIN）、`PATCH /api/v1/feedback/{id}/status`（仅 ADMIN）。

## Testing Decisions

- 只测外部行为，不测实现细节。
- **主 seam（Service 层）**：`FeedbackService` 用 `@SpringBootTest` + `@Transactional` + 真实本地存储 + 真实库测试。覆盖：提交与分类校验、普通成员只能查自己的反馈、ADMIN 查全量、状态机流转与「已解决」重开、回复方向通知（ADMIN→提交人、提交人→所有 ADMIN）、状态变更通知、限流、审计落库。
- **次 seam（Controller 授权）**：`FeedbackControllerSecurityTests` 用 `@WithMockUser`/`@WithAnonymousUser` + `@MockitoBean` Service，断言 `ADMIN` 才能全量列表与改状态、普通成员只能提交/看自己/回复自己的反馈、匿名一律拒绝。
- **通知链路**：验证新反馈/回复/状态变更确实调用通知投递，且未绑定企微的账号只落站内信（对齐现有 `notification` 测试）。
- 先例：`TeachingServiceTests`、`DocServiceTests`、`DocControllerSecurityTests`。

## Out of Scope

- 匿名 / 站外访客反馈（本期仅登录成员）。
- 工单指派（assignee）、优先级、标签。
- 邮件通道（沿用企业微信，不做邮件外发）。
- 反馈删除 / 撤回 / 自动归档。
- 贴图之外的附件文件上传。
- 满意度回访、SLA、反馈统计报表。

## Further Notes

- `CONTEXT.md` 术语表目前只覆盖「教学资料」，需补「消息中心 / 反馈 / 工单 / 待处理 / 处理中 / 已解决」等术语（符合 `docs/agents/domain.md` 的约定）。
- 表结构迁移需新增 Flyway 脚本（现有到 V55，新增 V56），遵循「不修改已发布迁移」。
- 反馈写操作走 `AuditInterceptor`；按仓库约定「API 前缀 `/api/v1`、错误用 `BusinessException` + `ErrorCode`、时间用 `Asia/Shanghai`」执行。
