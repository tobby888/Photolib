# PhotoLib 全流程测试方案与执行报告

> 文档版本：v1.2
> 编写日期：2026-07-02
> 测试对象：PhotoLib 当前工作区版本
> 执行原则：先完成测试设计，再在独立测试数据库中执行；不修改现有业务库数据。

## 1. 测试目标

验证 PhotoLib 从空库初始化到完整业务闭环的正确性、安全性和可用性，覆盖：

1. 系统初始化、数据库迁移、管理员引导和首次改密；
2. 管理员创建 `MINISTER`（部长）和 `CAMPUS_MANAGER`（校区负责人）两个不同权限账号；
3. 账户生命周期、角色权限和校区数据隔离；
4. 项目与图片需求完整状态流；
5. 本地对象存储等价验证及生产 OSS 配置契约；
6. 单图上传、元数据、检索、下载、归档、删除与批量上传；
7. 图片采用/取消采用及“已采用”标记；
8. 工时新增、编辑、提交、退回、再次提交、确认和统计；
9. 工时 XLSX 下载与图片 ZIP 下载；
10. 站内通知、已读状态、邮件开关、失败重试和管理员告警；
11. 关键异常、权限越权、并发版本冲突、输入边界及审计。

## 2. 范围与测试策略

### 2.1 本轮范围

- 前端：React 页面、路由守卫、角色菜单、表单与状态展示。
- 后端：REST API、鉴权、业务状态机、校验、异步任务。
- 数据库：Flyway V1～V6 初始化、约束与业务数据持久化。
- 文件：使用 `local` 存储模式验证与 OSS 相同的上传票据、PUT、下载 URL 和导出流程。
- 回归：前端生产构建、后端自动化测试。

### 2.2 环境限制

- 本轮不得向真实阿里云 OSS 或 DirectMail 发送数据，除非另行提供专用测试凭据。
- 因此 OSS 的“应用层完整流程”在本地对象存储上实测；真实 OSS 的 Bucket 权限、CORS、RAM 最小权限和 URL 到期在专用云测试环境执行。
- 邮件关闭时仍必须生成站内通知；真实邮件送达、垃圾箱和 DirectMail 控制台记录属于云环境验收项。

### 2.3 测试层次

- API 自动验证：主流程、状态机、权限、边界、异步导出及数据断言。
- UI 手工/浏览器验证：登录、首次改密、菜单权限、管理员建号、项目需求主链路、通知铃铛和下载入口。
- 构建回归：`npm run build`、`backend/mvnw.cmd test`。

## 3. 角色、测试账号与数据

| 代号 | 角色 | 用户名 | 校区 | 用途 |
|---|---|---|---|---|
| A | ADMIN | 隔离环境初始化账号 | 全部 | 初始化、建号、系统管理、兜底权限 |
| M | MINISTER | `qa_minister` | 无 | 项目、需求、采用、工时审批、统计导出 |
| C1 | CAMPUS_MANAGER | `qa_campus_a` | QA-A | 接单、上传图片、填报工时 |
| C2 | CAMPUS_MANAGER | `qa_campus_b` | QA-B | 校区隔离与越权反例；按需创建 |

主流程数据：

- 校区：`QA-A / QA测试校区A`，隔离校区：`QA-B / QA测试校区B`。
- 项目：`QA-20260702-全流程项目`。
- 需求：`QA-A 校园活动图片需求`，截止时间设为当前时间后 7 天。
- 图片：有效 JPG 一张，拍摄者学号 `QA2026001`，拍摄者姓名 `测试摄影师`。
- 工时：拍摄 60 分钟、修图 30 分钟；退回后修改为拍摄 75 分钟、修图 45 分钟。

测试密码只在隔离环境运行时生成和保存，不写入本文档或版本库。

## 4. 准入、准出与判定

### 4.1 准入条件

- MySQL 可连接，测试账号具备创建/删除独立测试库权限。
- 8080/5173 当前服务不受影响；隔离服务使用其他端口。
- 前后端能启动，健康检查返回 `UP`。
- 测试图片无隐私数据，允许写入本机测试目录。

### 4.2 结果定义

- **通过**：实际结果与预期一致，且无额外错误。
- **失败**：功能、权限、数据或展示与预期不一致。
- **阻塞**：受外部凭据、网络或前置缺陷影响无法执行。
- **不适用**：当前部署明确关闭该能力，且有替代验证。

### 4.3 准出条件

- P0/P1 用例全部执行；核心业务链路无阻塞。
- 不存在未关闭的致命或严重缺陷。
- 所有失败/阻塞项均记录复现步骤、实际结果和影响。
- 构建与现有自动化测试通过。

## 5. 业务状态机

```text
项目：DRAFT -> ACTIVE -> COMPLETED
                 \------> CANCELLED
      DRAFT ------------> CANCELLED
      COMPLETED --(仅管理员重新开放)--> ACTIVE

需求：DRAFT -> PUBLISHED -> ACCEPTED -> SUBMITTED -> COMPLETED
         \           \          \           \------> CANCELLED
          \-----------\----------\------------------> CANCELLED

工时：DRAFT -> SUBMITTED -> CONFIRMED
                  \-----> REJECTED -> SUBMITTED

图片：UPLOADING -> PROCESSING -> AVAILABLE <-> ARCHIVED
                                   \-------> DELETED（未被采用时）
```

## 6. 端到端执行顺序

1. 创建空测试库，启动隔离后端，确认 Flyway V1～V6 和唯一管理员。
2. 管理员登录并完成首次改密。
3. 管理员创建 QA-A 校区、部长 M、校区负责人 C1；验证两账号首次改密。
4. M 创建并激活项目，创建 QA-A 需求草稿并发布。
5. C1 收到通知、接受需求、上传图片、填写元数据、填报并提交工时。
6. C1 提交需求；M 验收需求并完成。
7. M 采用图片，检查图库“已采用”标记和排行。
8. M 退回一条工时；C1 修改并再次提交；M 确认。
9. M 导出工时 XLSX，并创建图片 ZIP 下载任务。
10. 验证通知已读、全部已读、邮件关闭行为和审计记录。
11. 完成项目，验证锁定；管理员重新开放，验证恢复业务能力。
12. 执行权限反例、边界、构建与自动化回归。

## 7. 详细测试用例

> 下列表格状态已按第 9 节执行记录回填；“待执行”仅表示尚无执行证据的用例。后续专项补测结果已同步到对应行。

### 7.1 初始化与认证

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| INIT-01 | P0 | 使用空数据库启动后端 | V1～V6 顺序执行；服务启动成功；健康检查为 `UP` | 通过 |
| INIT-02 | P0 | 查询空库初始化用户 | 仅存在一个启用的 `ADMIN`；`mustChangePassword=true` | 通过 |
| INIT-03 | P0 | 使用错误密码登录 | 返回 401；不泄露账号是否存在 | 通过 |
| INIT-04 | P0 | 管理员用初始密码登录 | 登录成功，但只能进入首次改密流程 | 通过 |
| INIT-05 | P0 | 首次改密传错误初始密码 | 返回 401，密码不改变 | 通过 |
| INIT-06 | P0 | 首次改密为合规新密码后重新登录 | 修改成功；旧密码失效；新密码可登录；`mustChangePassword=false` | 通过 |
| INIT-07 | P1 | 已完成首次改密后再次调用首次改密接口 | 返回状态冲突 | 通过 |
| INIT-08 | P1 | 修改本人密码，分别测试错误旧密码与正确旧密码 | 错误旧密码失败；正确旧密码成功；旧会话按设计处理 | 待执行 |
| INIT-09 | P1 | 刷新令牌、退出后再刷新 | 登录态可刷新；退出后刷新令牌失效 | 通过 |
| INIT-10 | P1 | 未携带令牌访问受保护接口 | 返回 401 | 通过 |

### 7.2 账户、校区与权限

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| ACC-01 | P0 | 管理员创建 QA-A 校区 | 创建成功，代码唯一且启用 | 通过 |
| ACC-02 | P1 | 重复创建相同校区代码 | 返回重复资源/约束错误，不产生重复数据 | 通过 |
| ACC-03 | P0 | 管理员创建 M（MINISTER） | 返回一次性初始密码；账号默认启用并要求首次改密 | 通过 |
| ACC-04 | P0 | 管理员创建 C1（CAMPUS_MANAGER + QA-A） | 创建成功并正确关联 QA-A | 通过 |
| ACC-05 | P0 | 创建未关联校区的 CAMPUS_MANAGER | 校验失败 | 通过 |
| ACC-06 | P1 | 创建关联已停用校区的 CAMPUS_MANAGER | 校验失败 | 待执行 |
| ACC-07 | P1 | 用户名非法、过短、重复；邮箱格式错误 | 均被拒绝，错误字段明确 | 通过 |
| ACC-08 | P0 | M/C1 首次登录并改密 | 均成功；角色、校区和菜单正确 | 通过 |
| ACC-09 | P0 | M 尝试创建/编辑/停用用户或校区 | 返回 403；页面不显示对应操作入口 | 失败 |
| ACC-10 | P0 | C1 尝试访问用户列表、审计、通知设置 | 返回 403；页面不显示系统管理入口 | 失败 |
| ACC-11 | P1 | 管理员重置 C1 密码 | 返回新初始密码；原密码失效；再次强制首次改密 | 通过 |
| ACC-12 | P1 | 停用 C1 后登录/刷新；再启用 | 停用后不可登录且会话失效；启用后可登录 | 通过 |
| ACC-13 | P0 | 尝试停用唯一启用管理员 | 操作被拒绝 | 通过 |
| ACC-14 | P1 | 使用旧 `version` 更新用户/校区 | 返回版本冲突，较新数据不被覆盖 | 通过 |

### 7.3 项目与需求全流程

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| FLOW-01 | P0 | M 创建 DRAFT 项目并编辑标题/说明 | 创建、编辑成功；创建者和版本正确 | 通过 |
| FLOW-02 | P0 | 将项目 DRAFT→ACTIVE | 状态和版本更新 | 通过 |
| FLOW-03 | P1 | 新建项目直接设为 COMPLETED；非法状态跳转 | 均返回状态冲突/校验失败 | 通过 |
| FLOW-04 | P0 | M 在 ACTIVE 项目创建 QA-A 需求草稿 | 字段、校区、数量、未来截止时间正确 | 通过 |
| FLOW-05 | P1 | 截止时间为过去、数量 0/10001、空标题 | 校验失败 | 通过 |
| FLOW-06 | P0 | 编辑草稿并发布 | DRAFT→PUBLISHED；QA-A 负责人收到通知 | 通过 |
| FLOW-07 | P0 | C1 接受 QA-A 需求 | PUBLISHED→ACCEPTED；参与人出现 C1；创建者收到通知 | 通过 |
| FLOW-08 | P1 | C1 重复接受同一需求 | 返回重复资源，不产生重复参与人 | 通过 |
| FLOW-09 | P0 | QA-B 负责人接受 QA-A 需求 | 返回 403，校区隔离有效 | 通过 |
| FLOW-10 | P1 | C1 在无图片/工时前退出后重新接受 | 退出成功，参与人移除；可再次接受 | 待执行 |
| FLOW-11 | P1 | 已有图片或工时后退出 | 返回状态冲突，业务数据保留 | 待执行 |
| FLOW-12 | P0 | C1 在至少一张可用图片后提交需求 | ACCEPTED→SUBMITTED；创建者收到通知 | 通过 |
| FLOW-13 | P1 | 无可用图片时提交需求 | 返回状态冲突 | 通过 |
| FLOW-14 | P0 | M 完成已提交需求 | SUBMITTED→COMPLETED | 通过 |
| FLOW-15 | P1 | 取消未结束需求并记录原因 | 状态变为 CANCELLED；结束后不可重复取消 | 待执行 |
| FLOW-16 | P0 | 完成项目并尝试新增需求/采用/改需求状态 | 项目变为 COMPLETED；相关业务写操作锁定 | 通过 |
| FLOW-17 | P0 | M 尝试重新开放项目；管理员执行重新开放 | M 返回 403；管理员可 COMPLETED→ACTIVE | 失败 |
| FLOW-18 | P1 | 有业务数据的项目执行删除 | 删除被拒绝，只允许取消 | 待执行 |

### 7.4 OSS/对象存储与图片

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| OSS-01 | P0 | C1 为已接受需求申请 JPG 上传票据 | 返回图片 ID、PUT URL、对象键；初态 UPLOADING | 通过 |
| OSS-02 | P0 | 按票据向对象存储 PUT，随后确认上传 | PUT 成功；确认后进入处理并最终 AVAILABLE | 通过 |
| OSS-03 | P1 | PUT 内容与声明 SHA-256 不一致 | 完成上传时拒绝或处理失败，不产生可用图片 | 待执行 |
| OSS-04 | P1 | 申请 GIF/TXT、空文件、超过 100 MiB | 返回不支持类型/文件过大 | 通过 |
| OSS-05 | P0 | 非需求参与人申请关联该需求的上传票据 | 返回 403 | 通过 |
| OSS-06 | P0 | 查询图片详情和图库筛选 | 标题、拍摄者、项目、需求、校区、状态正确 | 通过 |
| OSS-07 | P1 | C1 编辑本人上传图片元数据；他人越权编辑 | 本人成功；无权用户返回 403 | 待执行 |
| OSS-08 | P0 | 获取原图下载 URL 并下载 | URL 可用，文件哈希/格式正确；审计产生 PHOTO_DOWNLOAD | 通过 |
| OSS-09 | P1 | 非授权校区负责人查看/下载图片 | 返回 403 或列表不可见 | 待执行 |
| OSS-10 | P0 | M 归档、恢复图片 | AVAILABLE↔ARCHIVED，列表状态同步 | 通过 |
| OSS-11 | P1 | 删除未采用图片；删除已采用图片 | 未采用可删除；已采用只允许归档 | 通过 |
| OSS-12 | P1 | FILES 批量上传 2 张并逐张填元数据 | 批次完成，项目/需求关联和逐张元数据正确 | 待执行 |
| OSS-13 | P1 | FILES 模式 0/101 张；ZIP 超 1.5 GB | 边界请求被拒绝 | 待执行 |
| OSS-14 | P1 | 上传包含 JPG/PNG 的测试 ZIP | 解压、处理、批次状态和成功计数正确 | 待执行 |
| OSS-15 | 云验收 | 私有 OSS Bucket + 正确 RAM/CORS 跑 OSS-01～14 | 不公开读；PUT/GET 签名正常；无多余 RAM 权限 | 阻塞：需专用 OSS |
| OSS-16 | 云验收 | 修改/等待预签名 URL 过期后访问 | 返回签名过期，无法继续上传/下载 | 阻塞：需专用 OSS |

### 7.5 图片采用与被引标记

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| ADOPT-01 | P0 | M 在 ACTIVE 项目采用可用图片 | 采用记录创建；图片视图 `adopted=true`/采用次数更新 | 通过 |
| ADOPT-02 | P0 | 图库按采用状态查看，打开图片详情 | 列表和详情均显示“已采用”标记 | 失败 |
| ADOPT-03 | P1 | 同一项目重复采用同一图片 | 返回重复资源，不重复计数 | 通过 |
| ADOPT-04 | P1 | C1 尝试采用/取消采用 | 返回 403；UI 无采用操作 | 失败 |
| ADOPT-05 | P1 | 采用非 AVAILABLE、已删除或不存在图片 | 操作失败且整批保持原子性 | 待执行 |
| ADOPT-06 | P0 | 查询采用排行 | `QA2026001` 采用数增加 1 | 通过 |
| ADOPT-07 | P0 | 取消采用 | 记录标记取消；图片采用标记/计数恢复 | 通过 |
| ADOPT-08 | P1 | 项目完成后取消采用；取消后重新采用 | 完成后取消被拒绝；重新开放后可按规则操作 | 通过 |

### 7.6 工时

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| WORK-01 | P0 | C1 为参与需求新增 DRAFT 工时 60+30 分钟 | 创建成功，归属 C1/需求/项目/校区正确 | 通过 |
| WORK-02 | P1 | 非参与人填报；未来日期；两类分钟均为 0 | 均被拒绝 | 通过 |
| WORK-03 | P1 | 分钟数 -1/1441；备注超 1000 字 | 校验失败 | 待执行 |
| WORK-04 | P0 | 编辑 DRAFT 后提交 | DRAFT→SUBMITTED；版本递增 | 通过 |
| WORK-05 | P1 | 已提交工时继续编辑/删除 | 操作被拒绝 | 通过 |
| WORK-06 | P0 | M 退回工时并填写原因 | SUBMITTED→REJECTED；C1 收到含原因通知 | 通过 |
| WORK-07 | P0 | C1 将工时改为 75+45 并再次提交 | REJECTED 可编辑；再次变为 SUBMITTED | 通过 |
| WORK-08 | P0 | M 确认工时 | SUBMITTED→CONFIRMED；C1 收到通知 | 通过 |
| WORK-09 | P1 | 对 DRAFT/REJECTED 直接确认；重复确认 | 返回状态冲突 | 通过 |
| WORK-10 | P0 | C1/M/A 分别查询工时列表 | C1 仅见本人；M/A 按权限可见审批范围 | 待执行 |
| WORK-11 | P1 | 使用过期 version 提交/确认/退回 | 返回版本冲突，不覆盖新状态 | 待执行 |
| WORK-12 | P0 | 查询成员统计和总览 | 仅 CONFIRMED 工时计入；分钟合计为 120 | 通过 |

### 7.7 工时下载与图片批量下载

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| EXPORT-01 | P0 | M 创建成员统计 XLSX 导出任务 | 返回异步任务 ID，最终状态 SUCCEEDED | 通过 |
| EXPORT-02 | P0 | 轮询任务并下载 XLSX | 下载 URL 有效；文件可打开；表头、C1、75/45/120 分钟正确 | 通过 |
| EXPORT-03 | P1 | 使用日期、项目、校区条件导出 | 导出行与筛选条件一致 | 待执行 |
| EXPORT-04 | P0 | C1 尝试创建统计导出 | 返回 403，UI 无统计导出入口 | 失败 |
| EXPORT-05 | P1 | 其他用户读取非本人创建的导出任务 | 返回 403（管理员除外） | 待执行 |
| EXPORT-06 | P0 | M 选择 1 张图片创建 ZIP 下载任务 | 最终 SUCCEEDED；ZIP 可打开且包含正确图片 | 通过 |
| EXPORT-07 | P1 | 图片批量下载 0 张/201 张 | 校验失败 | 通过 |
| EXPORT-08 | P1 | 下载已过期任务 URL | URL 失效，需重新生成任务 | 待执行 |

### 7.8 通知、邮件与审计

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| NOTIFY-01 | P0 | 管理员创建 M/C1 | 新用户各收到账号创建站内通知 | 通过 |
| NOTIFY-02 | P0 | 发布需求 | QA-A 启用负责人收到通知；其他校区不收到 | 通过 |
| NOTIFY-03 | P0 | 接受/提交需求 | 需求创建者分别收到通知 | 通过 |
| NOTIFY-04 | P0 | 工时退回/确认 | 填报人收到对应通知，退回原因正确 | 通过 |
| NOTIFY-05 | P0 | 查询未读数、单条已读、全部已读 | 未读数正确递减至 0；重复操作幂等 | 通过 |
| NOTIFY-06 | P1 | 用户读取或标记他人的通知 | 不可见/返回资源不存在或 403 | 待执行 |
| NOTIFY-07 | P0 | 部署级邮件关闭时触发以上事件 | 站内消息正常；不尝试真实邮件；管理端显示不可投递 | 通过 |
| NOTIFY-08 | P1 | M/C1 访问邮件设置、日志、重试接口 | 返回 403 | 失败 |
| NOTIFY-09 | 云验收 | 开启 DirectMail，发送测试邮件 | 收件箱收到；日志为成功；发件地址正确 | 阻塞：需专用 DirectMail |
| NOTIFY-10 | 云验收 | 制造投递失败并观察三次重试 | 三次失败后产生管理员告警；修复后可手动重试 | 阻塞：需专用 DirectMail |
| NOTIFY-11 | P0 | 管理员定点向任意启用成员发送富文本消息 | 仅目标成员收到一条 `DIRECT_MESSAGE`，发送者与正文正确 | 通过（自动化） |
| NOTIFY-12 | P0 | 部长广播富文本消息 | 所有启用成员各收到一条 `BROADCAST_MESSAGE` | 通过（自动化） |
| NOTIFY-13 | P0 | 校区负责人尝试广播或定点发送管理消息 | 权限拒绝，不产生任何消息 | 通过（自动化） |
| NOTIFY-14 | P1 | 定点发送给停用成员 | 返回“成员不存在或已停用”，不产生消息 | 通过（自动化） |
| NOTIFY-15 | P0 | 广播后其中一名成员全部已读 | 仅该成员消息变为已读，其他成员不受影响 | 通过（自动化） |
| NOTIFY-16 | P0 | 富文本包含脚本、事件属性和合法消息图片 | 危险内容被清理，合法格式及受控图片地址保留 | 通过（自动化） |
| NOTIFY-17 | P0 | 上传合法消息图片及伪造图片 | 合法图片写入配置的对象存储并可读取；伪造内容被拒绝 | 通过（自动化） |
| AUDIT-01 | P0 | 管理员查询测试期间审计日志 | 登录/管理/下载/关键写操作按设计留痕 | 通过 |
| AUDIT-02 | P1 | M/C1 查询审计日志 | 返回 403 | 失败 |

#### 7.8.1 消息通知自动化测试

自动化测试类：`backend/src/test/java/cn/photolib/notification/NotificationServiceTests.java`。测试使用 H2 隔离数据库和事务回滚，不发送真实邮件。

| ID | 对应测试方法 | 覆盖内容 | 预期结果 |
|---|---|---|---|
| NT-UNIT-01 | `notifyUser_withoutEmail_shouldStillCreateInAppNotification` | 无邮箱用户的站内通知、HTML 文本转换、需求跳转地址 | 生成一条未读消息，内容和 `/requests` 跳转正确 |
| NT-UNIT-02 | `notifyUser_withEmail_shouldCreateInAppNotificationAndMailLog` | 站内消息与邮件日志双通道 | 站内消息生成，邮件日志为 `PENDING`，工时跳转为 `/worklogs` |
| NT-UNIT-03 | `notifyUser_forDisabledUser_shouldNotCreateNotification` | 停用用户过滤 | 不产生消息，未读数为 0 |
| NT-UNIT-04 | `listForUser_shouldIsolateUsersAndFilterUnreadNotifications` | 用户隔离、未读筛选 | 仅返回当前用户数据，未读列表排除已读消息 |
| NT-UNIT-05 | `markRead_shouldBeIdempotentAndDecreaseUnreadCountOnce` | 单条已读及重复操作 | 首次设置 `readAt`，重复操作不改变时间，未读数为 0 |
| NT-UNIT-06 | `markRead_shouldNotAllowReadingAnotherUsersNotification` | 跨用户越权保护 | 返回“通知不存在”，目标用户消息保持未读 |
| NT-UNIT-07 | `markAllRead_shouldOnlyUpdateCurrentUsersNotifications` | 全部已读的用户边界与幂等性 | 当前用户全部已读，其他用户不受影响，重复操作安全 |

#### 7.8.2 广播与定点发送专项自动化测试

当前角色模型中的 `MINISTER` 统一代表正/副部长。专项测试使用 H2 隔离数据库、事务回滚和本地对象存储，不调用真实云 OSS。

| ID | 测试类/方法 | 覆盖内容 | 对应用例 |
|---|---|---|---|
| MM-UNIT-01 | `ManagementMessageControllerTests.admin_shouldSendDirectMessageToAnyEnabledMember` | 管理员定点发送、目标隔离、发送者记录 | NOTIFY-11 |
| MM-UNIT-02 | `ManagementMessageControllerTests.minister_shouldBroadcastToAllEnabledMembers` | 部长广播到全部启用成员 | NOTIFY-12 |
| MM-UNIT-03 | `ManagementMessageControllerTests.campusManager_shouldNotSendManagementMessage` | 非授权角色被方法级权限拒绝 | NOTIFY-13 |
| MM-UNIT-04 | `NotificationServiceTests.sendMessage_directMessage_shouldOnlyReachTargetAndSanitizeHtml` | 定点分发、富文本清洗、合法图片地址 | NOTIFY-11、NOTIFY-16 |
| MM-UNIT-05 | `NotificationServiceTests.sendMessage_broadcast_shouldCreateIndependentNotificationForEveryEnabledUser` | 广播范围、停用成员过滤、独立已读状态 | NOTIFY-12、NOTIFY-15 |
| MM-UNIT-06 | `NotificationServiceTests.sendMessage_toDisabledUser_shouldFail` | 停用成员不可作为定点接收者 | NOTIFY-14 |
| MM-UNIT-07 | `NotificationServiceTests.sendMessage_withUnsafeEmptyContent_shouldFail` | 清洗后为空的恶意正文被拒绝 | NOTIFY-16 |
| MM-UNIT-08 | `NotificationServiceTests.getForUser_shouldRejectAnotherUsersMessage` | 消息详情归属隔离 | NOTIFY-11 |
| MM-UNIT-09 | `MessageImageControllerTests.upload_shouldStoreImageAndReturnStableAuthenticatedUrl` | 图片写入对象存储及稳定读取地址 | NOTIFY-17 |
| MM-UNIT-10 | `MessageImageControllerTests.upload_shouldRejectSpoofedImageContent` | MIME 与文件签名不一致时拒绝上传 | NOTIFY-17 |

### 7.9 UI、兼容性与回归

| ID | P | 场景与步骤 | 预期结果 | 状态 |
|---|---|---|---|---|
| UI-01 | P0 | 管理员登录、首次改密、创建校区与两个账号 | 表单、提示、跳转和数据均正确 | 待执行 |
| UI-02 | P0 | M 登录后检查菜单并创建项目/需求 | 仅显示部长可用功能；主流程可完成 | 通过 |
| UI-03 | P0 | C1 登录后检查菜单、接单、上传、填工时 | 仅显示校区负责人功能；主流程可完成 | 待执行 |
| UI-04 | P0 | 通知铃铛查看未读、点击已读/全部已读 | 徽标和列表即时同步 | 通过 |
| UI-05 | P1 | 刷新各主路由、退出后使用浏览器后退 | 刷新不丢路由；退出后受保护页不可访问 | 待执行 |
| UI-06 | P1 | 1280×720 与常见桌面宽度检查主表格/弹窗 | 无关键按钮遮挡、横向内容可访问 | 待执行 |
| REG-01 | P0 | 执行前端生产构建 | TypeScript 与 Vite 构建通过 | 通过 |
| REG-02 | P0 | 执行后端自动化测试 | Maven 测试全部通过 | 通过 |

## 8. 需求追踪矩阵

| 用户要求 | 对应用例 |
|---|---|
| OSS 测试 | OSS-01～16、EXPORT-06～08 |
| 账户测试 | INIT-02～10、ACC-01～14 |
| 需求、项目全流程 | FLOW-01～18、UI-02～03 |
| 工时测试 | WORK-01～12 |
| 图片被引/采用标记 | ADOPT-01～08 |
| 工时下载 | EXPORT-01～05 |
| 通知测试 | NOTIFY-01～17、UI-04、NT-UNIT-01～07、MM-UNIT-01～10 |
| 从初始化到两个不同权限账号 | INIT-01～06、ACC-01～08、UI-01 |

## 9. 执行记录

### 9.1 环境信息

| 项目 | 实际值 |
|---|---|
| Git 提交 | `854db20`（另含用户工作区未提交改动；测试未覆盖/覆盖这些改动） |
| 操作系统 | Windows / Windows PowerShell 5 |
| Java | 25.0.1（项目按 `release 21` 编译） |
| Node/npm | Node 24.12.0 / npm 11.6.2；Maven 隔离 Node 20.19.4 |
| MySQL | MySQL 8.0，隔离库 `photolib_qa_20260702_r4` |
| 后端测试端口 | `18080` |
| 前端测试地址 | `http://127.0.0.1:18080/api/v1/`（JAR 内静态前端） |
| 存储模式 | local（OSS 应用层等价验证） |
| 邮件模式 | 部署级关闭 |

### 9.2 汇总

| 结果 | 数量 |
|---|---:|
| 通过 | 77 |
| 失败 | 8 |
| 阻塞 | 4（专用 OSS/DirectMail 云验收） |
| 未执行 | 24 |
| 用例总数 | 113 |

补充说明：

- API 自动化实际执行 69 个断言：61 通过、8 失败；合并 `WORK-02A/B`、`FLOW-17A/B` 后对应 66 个文档用例。
- UI 补充执行 `UI-02`、`UI-04`，均通过；图片采用标记的 UI 结果归入已失败的 `ADOPT-02`。
- 回归执行 `REG-01`、`REG-02`，均通过。
- 第二轮补测 `INIT-09`、`ACC-11`、`ACC-12`、`ACC-14`、`OSS-06`、`ADOPT-07`、`NOTIFY-03`，均通过。
- 广播与定点发送补测新增 `NOTIFY-11`～`NOTIFY-17`，7 个文档用例均通过；对应 10 项专项断言见第 9.8 节。
- 工时 XLSX 的两张工作表、表头和数值均通过结构检查：工时为 75/45/120 分钟，被引图片为 1 张；公式错误扫描为 0。

### 9.3 本轮已执行用例索引

通过：

`INIT-01`、`INIT-02`、`INIT-03`、`INIT-04`、`INIT-05`、`INIT-06`、`INIT-07`、`INIT-09`、`INIT-10`、
`ACC-01`、`ACC-02`、`ACC-03`、`ACC-04`、`ACC-05`、`ACC-07`、`ACC-08`、`ACC-11`、`ACC-12`、`ACC-13`、`ACC-14`、
`FLOW-01`、`FLOW-02`、`FLOW-03`、`FLOW-04`、`FLOW-05`、`FLOW-06`、`FLOW-07`、`FLOW-08`、
`FLOW-09`、`FLOW-12`、`FLOW-13`、`FLOW-14`、`FLOW-16`、
`OSS-01`、`OSS-02`、`OSS-04`、`OSS-05`、`OSS-06`、`OSS-08`、`OSS-10`、`OSS-11`、
`ADOPT-01`、`ADOPT-03`、`ADOPT-06`、`ADOPT-07`、`ADOPT-08`、
`WORK-01`、`WORK-02`、`WORK-04`、`WORK-05`、`WORK-06`、`WORK-07`、`WORK-08`、`WORK-09`、`WORK-12`、
`EXPORT-01`、`EXPORT-02`、`EXPORT-06`、`EXPORT-07`、
`NOTIFY-01`、`NOTIFY-02`、`NOTIFY-03`、`NOTIFY-04`、`NOTIFY-05`、`NOTIFY-07`、
`AUDIT-01`、`UI-02`、`UI-04`、`REG-01`、`REG-02`。

失败：

`ACC-09`、`ACC-10`、`FLOW-17`、`ADOPT-02`、`ADOPT-04`、`EXPORT-04`、`NOTIFY-08`、`AUDIT-02`。

阻塞：

`OSS-15`、`OSS-16`、`NOTIFY-09`、`NOTIFY-10`（缺少专用阿里云 OSS/DirectMail 测试资源）。

### 9.4 关键执行证据

- 空 MySQL 库启动时 Flyway 按 V1、V2、V3 顺序执行，最终 schema 版本为 3，健康检查为 `UP`。
- 管理员完成首次改密后创建部长和校区负责人；两个账号均被强制首次改密。
- 主链路完成：项目激活 → 需求发布/接单 → JPG 直传 → 异步处理为 AVAILABLE → 工时退回/修改/再提交/确认 → 需求完成 → 图片采用 → XLSX/ZIP 导出 → 项目完成/管理员重新开放。
- 下载原图 SHA-256 与上传文件一致；图片 ZIP 含 1 个正确文件。
- 部署级邮件关闭时，账号、需求、工时站内通知均正常生成；通知“全部已读”后未读数为 0。
- 前端 `npm run build` 通过。后端通过直接执行资源、编译与 Surefire 目标完成 8 个测试，0 失败、0 错误、0 跳过。
- 常规 `mvnw.cmd test` 曾因已运行的 Vite 占用 `node_modules/@esbuild/win32-x64/esbuild.exe` 而在 `npm ci` 阶段失败；未停止用户现有开发服务，改为独立后端测试目标后全部通过。该项判定为环境文件锁，不计产品缺陷。

### 9.5 缺陷清单

| 缺陷 ID | 严重度 | 关联用例 | 标题 | 状态 |
|---|---|---|---|---|
| BUG-001 | 严重 | ACC-09、ACC-10、FLOW-17、ADOPT-04、EXPORT-04、NOTIFY-08、AUDIT-02 | 方法级 `@PreAuthorize` 拒绝被全局异常处理器包装为 HTTP 500，而不是 403；服务端记录 `AuthorizationDeniedException` 堆栈 | 待修复 |
| BUG-002 | 严重 | ADOPT-02 | 图片已成功采用且排行计数为 1，但图片列表/详情 API 不返回 `adopted` 或 `adoptionCount`，图库卡片和详情也没有“已采用/被引”标记 | 待修复 |
| BUG-003 | 一般 | FLOW-01 | `POST /projects` 创建响应的 `version` 为 `null`；若客户端直接用于更新，会把 `null` 传给基本类型 `int` 并触发 HTTP 500。重新 GET 后版本才为 1 | 待修复 |
| BUG-004 | 一般 | EXPORT-02 | XLSX 内容正确，但两个工作表均使用默认窄列宽，负责人、校区、表头和拍摄者信息在视觉预览中被截断 | 待修复 |

### 9.6 最终结论

PhotoLib 的核心数据链路可运行：系统能从空库初始化，管理员能创建不同权限账号，项目/需求、图片上传下载、工时审批、图片采用、统计导出和站内通知均完成闭环；前后端回归也通过。

当前版本**不建议直接作为验收完成版发布**，原因是两个关键问题尚未修复：

1. 多个越权请求返回 500，破坏权限接口契约并产生无意义的服务端错误告警；
2. 用户明确要求的图片“被引/已采用”标记在 API 和 UI 中均不存在。

建议先修复 BUG-001、BUG-002，并补齐对应自动化测试；随后修复版本字段和 XLSX 列宽问题，再重跑本报告中的失败用例和剩余 P0/P1 用例。真实 OSS 与 DirectMail 的 4 个云验收用例仍需在专用测试账号下补测。

### 9.7 消息通知自动化补测（2026-07-03）

执行命令：

```powershell
cd backend
.\mvnw.cmd -Dtest=NotificationServiceTests test
.\mvnw.cmd test
```

执行结果：

| 测试范围 | 通过 | 失败 | 错误 | 跳过 | 结论 |
|---|---:|---:|---:|---:|---|
| 消息通知专项测试 | 7 | 0 | 0 | 0 | 通过 |
| 后端完整回归 | 86 | 0 | 0 | 1 | 通过 |

完整回归共发现 87 个测试，其中 86 个通过、1 个阿里云 OSS 集成测试因无专用云凭据按设计跳过。Flyway 已在空 H2 数据库中成功执行至 V5；消息通知专项用例全部通过，未发现新增回归缺陷。

### 9.8 广播与定点发送专项测试（2026-07-03）

执行命令：

```powershell
cd backend
.\mvnw.cmd resources:resources compiler:compile resources:testResources compiler:testCompile surefire:test "-Dtest=NotificationServiceTests,ManagementMessageControllerTests,MessageImageControllerTests"
```

执行环境：

- 数据库：H2 内存隔离库，Flyway V1～V6 全量迁移；
- 对象存储：`local` 测试模式，与 OSS 使用同一 `ObjectStorageService` 接口；
- 邮件与真实云 OSS：本专项不调用；
- 数据清理：每个测试事务自动回滚，测试图片在断言后主动删除。

执行结果：

| 测试类 | 执行数 | 通过 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| `ManagementMessageControllerTests` | 3 | 3 | 0 | 0 | 0 |
| `NotificationServiceTests` | 12 | 12 | 0 | 0 | 0 |
| `MessageImageControllerTests` | 2 | 2 | 0 | 0 | 0 |
| **合计** | **17** | **17** | **0** | **0** | **0** |

其中广播与定点发送新增/关联专项断言 10 项，原站内通知回归断言 7 项。管理员定点发送、部长广播、非授权角色拒绝、停用成员校验、收件人隔离、独立已读状态、富文本安全清洗、图片对象存储及伪造图片拦截均通过，未发现缺陷。
