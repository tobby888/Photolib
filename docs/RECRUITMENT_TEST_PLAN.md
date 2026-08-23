# 新成员招募功能测试计划

本文档覆盖招募任务、匿名报名、动态表单、原图/ZIP 上传、学号去重、权限和内部详情。所有端到端测试必须使用隔离数据库与本地对象存储；真实 OSS 只执行专门的 CORS/签名验证，禁止指向生产 Bucket。

## 1. 自动化测试门禁

提交前必须全部通过：

```powershell
npm test
npm run build
npm run lint
cd backend
.\mvnw.cmd '-Dexec.skip=true' test
$nativeOutputRoot = Join-Path (Get-Location) 'target/generated-resources'
$nativeBuildRoot = Join-Path (Get-Location) 'target/native-build'
.\mvnw.cmd clean
& '.\native\build-native.ps1' -OutputDirectory $nativeOutputRoot -BuildDirectory $nativeBuildRoot
.\mvnw.cmd '-Dexec.skip=true' package
```

`-Dexec.skip=true` 只跳过 Maven 内的原生图片处理器构建步骤；Java、Flyway、前端生产构建和所有测试仍会执行。`clean` 会删除 `target/generated-resources`，所以从干净目录验证时必须先用绝对输出目录运行 `backend/native/build-native.ps1`，再执行带 `-Dexec.skip=true` 的 `package`；也可以在具备完整 Zig、CMake、Ninja、NASM 与 PowerShell 工具链的发布环境直接执行不带跳过参数的 `clean package`。

招募定向测试应至少覆盖以下测试类（以仓库最终类名为准）：

```powershell
cd backend
.\mvnw.cmd '-Dexec.skip=true' '-Dtest=RecruitmentCoreTests,RecruitmentControllerSecurityTests,RecruitmentMigrationTests,RecruitmentUploadServiceTests,RecruitmentUploadProcessorRaceTests,RecruitmentUploadDispatchQueueTests,SafeImageZipExtractorTests,BatchUploadServiceTests,PermissionGroupServiceTests' test
```

## 2. 权限与任务生命周期

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| R-AUTH-01 | 管理员访问招募管理 | 可查看、创建、编辑、发布和关闭 |
| R-AUTH-02 | 内置部长访问招募管理 | 默认具有查看与发布权限 |
| R-AUTH-03 | 内置校区负责人访问 | 可查看任务和报名详情，不显示写操作 |
| R-AUTH-04 | 管理员给自定义权限组分配 `RECRUITMENT_PUBLISH` | 组内账号重新鉴权后可发布 |
| R-AUTH-05 | 撤销发布权限 | 前端隐藏写入口，伪造写请求返回 403 |
| R-AUTH-06 | 无 `RECRUITMENT_VIEW` 的自定义组 | 内部任务和报名详情返回 403 |
| R-TASK-01 | 创建合法草稿 | 保存 Markdown、时间、表单字段和上传设置 |
| R-TASK-02 | 结束时间不晚于开始时间 | 前后端均拒绝 |
| R-TASK-03 | 发布结束时间已过的草稿 | 拒绝发布 |
| R-TASK-04 | 发布后修改表单 schema | 拒绝，历史答案语义保持稳定 |
| R-TASK-05 | 两个用户用同一 version 并发更新 | 只允许一个成功，另一个返回 409 |
| R-TASK-06 | 提前关闭 | 公共列表立即不可见，既有报名仍可内部查看 |
| R-TASK-07 | 提交/上传创建/上传完成与关闭或截止时间修改并发 | 按“任务 → 草稿”行锁顺序串行化；锁后重验窗口，不会在关闭后出票或进入处理 |

## 3. 公开页面与时间边界

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| R-PUBLIC-01 | 未登录从登录页点击“招募新成员” | 进入 `/#/recruitment` |
| R-PUBLIC-02 | 已登录访问公开招募路由 | 直接重定向主页面；首次改密账号进入改密页 |
| R-PUBLIC-03 | 没有活跃任务 | 精确显示“当前暂无大规模招募任务哦，敬请期待” |
| R-PUBLIC-04 | 当前时刻早于开始时间 | 任务不公开 |
| R-PUBLIC-05 | 当前时刻等于开始时间 | 任务公开，可创建草稿 |
| R-PUBLIC-06 | 当前时刻等于结束时间 | 任务不再公开，出票和提交均拒绝 |
| R-PUBLIC-07 | 多个同时有效任务 | 均可选择并分别报名 |
| R-PUBLIC-08 | API 加载失败 | 显示错误和重试入口，不误显示提交成功 |

## 4. 动态表单与学号唯一性

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| R-FORM-01 | 编辑器添加、删除、上移、下移字段 | 稳定字段 ID 不变，顺序正确 |
| R-FORM-02 | 重复字段 ID、空标题、重复/空选项 | 发布前后端均拒绝 |
| R-FORM-03 | 短文本、长文本、日期、单选、多选 | 序列化和详情 Markdown 值一致 |
| R-FORM-04 | 缺失必填答案或伪造选项 | 最终提交拒绝 |
| R-FORM-05 | 学号为空、过长或含非法字符 | 聚焦学号并拒绝提交 |
| R-FORM-06 | 学号 `００１Ab-2` 与 `001ab-2` | NFKC/大写规范化后视为同一学号 |
| R-FORM-07 | 两个草稿并发提交同任务同学号 | 数据库仅一条申请，另一个得到明确 409 |
| R-FORM-08 | 同一学号报名不同任务 | 两次均可成功 |
| R-FORM-09 | 双击同一草稿提交 | 只有一个请求成功，另一个返回状态冲突；不生成重复记录 |
| R-FORM-10 | 答案含 `#`、`[]()`、反引号或 HTML | 详情 Markdown 按普通文本安全展示，不注入链接/图片/HTML |
| R-FORM-11 | 直接构造跨任务 draft/application 或改写规范化学号 | 数据库复合外键拒绝，不会读取其他任务草稿的附件 |

## 5. 原图与 ZIP 上传

所有边界值同时验证前端提示和后端最终边界。`100 MiB` 指 `100 * 1024 * 1024` 字节；ZIP 上限为十进制 `1,500,000,000` 字节；总展开上限为 `10 GiB`。

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| R-UP-01 | 1～100 张合法 JPG/JPEG/PNG | 直传、校验并保存成功 |
| R-UP-02 | 0 张、101 张、单张 100 MiB + 1 | 前后端拒绝 |
| R-UP-03 | 扩展名、Content-Type、魔数不一致 | 后端处理失败，不生成可用附件 |
| R-UP-04 | 声明大小与 OSS HEAD 实际大小不同 | 失败并删除临时对象 |
| R-UP-05 | 声明 SHA-256 与实际字节不同 | 失败并删除临时对象 |
| R-UP-06 | 上传成功后比较输入与最终对象 SHA-256 | 完全一致，证明没有压缩或重编码 |
| R-UP-07 | 合法 ZIP 含子目录、JPG/PNG 和非图片 | 跳过目录/非图片，保存有效图片 |
| R-UP-08 | ZIP 为 1.5 GB + 1 | 出票和完成阶段均拒绝 |
| R-UP-09 | ZIP 含 101 张有效图片 | 整批失败 |
| R-UP-10 | 单条目超过 100 MiB / 总展开超过 10 GiB | 整批失败 |
| R-UP-11 | 空 ZIP 或仅非图片 | 显示“没有 JPG/PNG 图片”并失败 |
| R-UP-12 | `../`、`..\\`、`/abs`、UNC、`C:/`、NUL 路径 | 拒绝，临时目录外无任何文件 |
| R-UP-13 | 伪造/跨草稿 capability token | 401/403，不泄露批次存在性或附件 |
| R-UP-14 | 任务在上传过程中到期或被关闭 | 后续完成/提交拒绝，草稿进入过期清理 |
| R-UP-15 | OSS stat/open/put/delete 暂时失败 | 状态可诊断；已提交附件不被清理 |
| R-UP-16 | ZIP 部分文件魔数无效 | 合法项保留，批次为部分成功并列出失败原因 |
| R-UP-17 | 伪造完整魔数但不是可解码图片 | 原生图片解析拒绝，公开响应只返回安全原因，服务端日志保留内部原因 |
| R-UP-18 | 最终对象 PUT 前后进程退出 | `object_key` 已先持久化；恢复任务复用同一精确 key，不产生不可追踪对象 |
| R-UP-19 | 立即删除临时对象后，在签名有效期内重放 PUT | 到期清理再次按精确 key 删除；包括已提交草稿，随后才清空数据库 key |
| R-UP-20 | 批次处于 `PROCESSING` 且上传签名已过期 | 过期清理不删除处理源；处理完成进入终态后再重删并清 key |
| R-UP-21 | 招募处理队列已满或应用重启 | 请求线程不执行原生解码；持久化的 `PROCESSING` 批次由恢复扫描重新派发 |
| R-UP-22 | 同一 `PROCESSING` 批次被事件和定时扫描同时发现 | 进程内只排队一次，任务结束后释放去重标记 |
| R-UP-23 | 最终对象 PUT 与过期清理/CAS 失败交错 | 只删除未被当前状态引用的精确 key；`SUCCEEDED`/`PROCESSING` 同 key 不误删，删除失败保留 key 重试 |
| R-UP-24 | 直接写入非法或小写 task/draft/batch/item 状态、非法 mode | MySQL/H2 的严格 CHECK 均拒绝，异步扫描不会被未知枚举行绕过 |

## 6. 内部详情、附件与隐私

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| R-DETAIL-01 | 三个内置权限组打开同一申请 | 学号和全部动态字段完整显示 |
| R-DETAIL-02 | 可用附件 | 显示文件名、类型、大小、图片预览和下载入口 |
| R-DETAIL-03 | 未登录访问内部详情或附件 | 401；公共接口不返回底层 object key |
| R-DETAIL-04 | 下载文件名含路径分隔符/控制字符 | Content-Disposition 安全，无响应头注入 |
| R-DETAIL-05 | 浏览器保存申请页后签名 URL 过期 | 重新加载详情获得新短期 URL |
| R-DETAIL-06 | 匿名提交产生审计日志 | 不记录答案正文、学号、草稿 token 或 OSS 签名 URL |

## 7. 浏览器与响应式检查

在 375 px、768 px 和桌面宽度各执行一次核心流程：

1. 登录页按钮可见，Tab 键可聚焦并进入公开页。
2. 表单编辑器的增删、排序、选项维护和 Markdown 预览可用。
3. 上传进度、后台处理轮询、部分失败与超时提示不会遮挡提交按钮。
4. 重复学号错误后，文字答案和已成功附件仍保留。
5. 刷新 Hash 子路由不会得到服务器 404。
6. 公开页、内部列表、申请详情无横向溢出；长字段和长文件名可换行。

真实 OSS 专项还必须在实际站点来源验证：预签名域名可访问、Bucket CORS 允许 PUT/GET/HEAD、请求 `Content-Type` 与签名完全一致、Bucket 保持私有。

## 8. 匿名接口防滥用与身份边界

- 草稿创建、上传批次出票和上传完成分别使用独立的进程内限流窗口；超过限制返回 HTTP 429 和 `RATE_LIMITED`。测试需确认不同动作互不占用配额，跟踪键数量始终有硬上限。
- 应用只使用 Servlet 容器提供的 `remoteAddr`，绝不直接信任客户端可伪造的 `X-Forwarded-For`。无效任务不会占用限流状态；私网/回环代理地址及有界状态耗尽时进程内限流 fail-open，避免把共享代理后的全部用户一起封禁。因此生产网关仍必须配置独立、分布式的 IP/路径限流；进程内限流只是纵深防护，不能代替网关。
- 创建草稿不会回答某学号是否已经提交。最终提交必须使用创建草稿时绑定的同一规范化学号，并由数据库唯一键裁决并发提交。
- 学号仅用于这张招募任务内的输入标识与去重。当前产品没有邮箱、短信、教务系统或其他身份验证流程，因此页面、日志和运营口径都不得把它描述为真实身份认证。

## 9. 2026-08-22 实测记录

自动化门禁结果：

- `npm test`：44/44 通过。
- `npm run lint`：通过，无 ESLint 错误。
- `npm run build`：TypeScript 与 Vite 生产构建通过。
- `mvn package`：364 项通过、0 失败、0 错误、2 项按既有环境条件跳过；V1～V30 空库迁移及带数据的 V27→V29→V30 升级均成功。
- 招募与兼容性专项共 60 项通过：核心 16、控制器权限 4、迁移 2、上传服务 11、PUT/CAS 竞态 4、派发去重 2、安全 ZIP 4、图库批量回归 10、权限组 7。
- Windows 与 Linux x86_64 原生图片组件均从干净构建目录生成，Windows 原生 PNG 编码测试通过；最终 `mvn package` 再次跑完 364 项测试并生成约 85.2 MB 的可执行 JAR。

隔离浏览器端到端结果：

- 使用临时 H2 数据库和临时本地对象存储完成管理员建表单、发布、匿名报名、内部查看与提前关闭，不连接生产数据库或生产 Bucket。
- 登录页入口、无任务精确文案、已登录访问公开路由重定向、发布后公开、关闭后立即隐藏均符合预期。
- 以全角和空格混合输入 `００１ ab-2`，服务端保存为 `001AB-2`；再次用该规范化学号提交被明确拒绝，内部申请数未增加。
- 图片直传和 ZIP 上传各完成一份有效申请。图片输入与最终对象均为 131,824 字节，SHA-256 均为 `18E61AD85D6CCFB2C00A36FB9AB3B534D396F6605329674CCC1F1FFE8B39C806`，确认未压缩、未转码。
- 内部详情完整显示学号、自定义答案、附件名、大小、图片预览和下载入口；答案中的 Markdown、HTML 与 URL 标记按普通文本转义，申请详情专用渲染器会把 GFM 自动链接降级为普通文本。
- 375 px、768 px、1440 px 三档检查公开表单和内部申请详情，`scrollWidth` 均未超过 `clientWidth`。修正招募页面自身的 Ant Design 弃用警告和未挂载表单警告后，继续操作未产生新的控制台错误记录。

未在本机执行真实 OSS 专项，因为该步骤需要部署站点来源、测试 Bucket 与有效临时凭据。上线前仍须按第 7 节验证私有 Bucket、CORS、PUT/GET/HEAD 和签名 `Content-Type`，并在网关配置分布式匿名接口限流。`npm ci` 同时报告仓库既有依赖树中 6 个 high 级漏洞，本次未未经评估执行破坏性 `npm audit fix`。
