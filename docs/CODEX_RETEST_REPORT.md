# Claude 分支复测报告

复测日期：2026-07-02  
分支：`claude/fix-tests-and-audit`（`ef75b4e`）  
基线：`master`（`854db20`）

## 结论

首次复测发现后端 **72 项测试中有 6 失败、13 错误**，且 ESLint 无法执行。问题现已修复：后端全量测试 **72/72 通过**，前端 ESLint 与生产构建通过，关键浏览器链路回归通过。

## 自动化测试

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| `npm run build` | 通过 | Vite 构建成功；主入口 chunk 约 812 KB，存在大于 500 KB 警告 |
| `npm run lint` | 通过 | ESLint 9 flat config，0 errors |
| `backend\mvnw.cmd test` | 通过 | 72 tests，0 failures，0 errors |
| npm 依赖审计 | 通过 | `npm ci` 报告 0 vulnerabilities |

### 首次复测的后端失败明细

- `AuthServiceTests`：8 项中 6 个错误。测试配置未提供 `photolib.auth.access-ttl` / `idle-ttl`，登录时在 `LocalDateTime.plus(...)` 触发 `NullPointerException: amountToAdd`。
- `CampusServiceTests`：重复校区编码用例断言了错误的业务异常消息，实际抛出数据库 `DuplicateKeyException`。
- `PhotoServiceTests`：9 项中 2 失败、4 错误。包括上传大小参数与生产限制不一致、测试数据引用不存在的 `uploaded_by=999`、异常消息断言不一致。
- `RequestServiceTests`：完成需求流程复用了过期版本号，提交时触发乐观锁冲突。
- `UserServiceTests`：8 项中 3 失败、2 错误。部分由认证 TTL 缺失引起，重复用户名用例同样把数据库唯一键异常误判为业务异常。

上述问题均已修复，包括测试环境参数、照片外键夹具、需求乐观锁版本、重复资源业务错误及不准确的异常断言。

## 浏览器页面操作测试

环境：Spring Boot + H2 内存库 + local storage，打包前端由后端在 `http://127.0.0.1:8080/api/v1/` 提供。

| 场景 | 结果 | 实际表现 |
| --- | --- | --- |
| 登录页加载 | 通过 | 中文文案、账号/密码输入框和登录按钮正常 |
| 管理员首次登录 | 通过 | 正确跳转至 `#/initial-password` |
| 弱密码校验 | 通过 | 显示“至少 10 位，并同时包含字母和数字” |
| 两次密码不一致 | 通过 | 显示“两次输入的密码不一致” |
| 首次改密 | 通过 | 改密成功并进入 `#/` 工作台 |
| 管理员导航 | 通过 | 工作台、项目、需求、图片、工时、统计、系统管理均显示 |
| 品牌名称/Slogan 保存 | 通过 | 保存后侧栏立即更新并显示成功提示 |
| 品牌设置持久化 | 通过 | 刷新 `#/admin` 后名称与 Slogan 保持 |
| 直接访问 `/login` | 失败 | 返回 Tomcat 404；部署和外链必须使用 `#/login` |

修复完成后再次使用隔离浏览器会话验证了登录、首次改密以及进入管理员工作台，结果通过。

本轮没有自动提交文件上传对话框，因此 Claude 报告中的自定义图片上传结论未被本轮独立复现。

## 文档一致性问题

- `docs/FINAL_TEST_REPORT.md` 和 `docs/SYSTEM_TEST_EXECUTION_REPORT.md` 声称测试编译/执行成功，但本次干净依赖安装后的真实 Maven 结果为失败。
- 报告称有“80+”测试，Surefire 实际发现 72 项。
- `docs/TESTING.md` 只覆盖品牌专项，不能代表系统功能测试。
- 现有报告把“系统功能测试 50%”与“整体 90%”并列，容易造成已经具备合并质量的误解。

## 后续建议

1. 为品牌 Controller 增加权限、边界、非法 MIME、超尺寸、伪造图片内容和持久化自动化测试。
2. 增加稳定的浏览器 E2E 套件，覆盖首次改密、角色权限、品牌设置和 Hash 路由刷新。
3. 拆分约 812 KB 的主入口 chunk，降低首屏加载成本。
