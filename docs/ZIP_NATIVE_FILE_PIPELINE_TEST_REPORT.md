# ZIP 与 Zig 文件处理管线测试报告

测试日期：2026-07-20

实现提交：`7fbe5c7a305ae454e0f5879343f549f90703e8dc`

测试平台：Windows 11 x86-64、Oracle JDK 25.0.1、Maven 3.9.16、Zig 0.16.0、CMake 4.2.1

Maven 前端工具链：Node.js 20.19.4、npm 10.8.2

## 1. 验证目标

本轮测试验证以下改造：

1. 所有生产 Zig 图片处理调用均由共享 `photoProcessingExecutor` 调度，线程数由 `PHOTO_PROCESSING_THREADS` 配置并限制为 1～32。
2. ZIP 图片条目使用 64 KiB 缓冲流式解压到 `PHOTO_PROCESSING_TEMPORARY_DIRECTORY`，不把整个条目放入 Java 堆。
3. Java 向 Zig 传递 UTF-8 本地文件路径；Zig 直接读取源文件并写入结果文件；Java 再流式上传对象存储。
4. 单张上传、ZIP 上传和预览图重建共用同一原生任务并发上限。
5. 成功与失败路径均清理 ZIP 条目、处理输入/输出等辅助文件；数据库不会向 API 暴露服务器本地路径。
6. Windows 与 Linux x86-64 原生库及前端静态资源均进入最终 Spring Boot Fat JAR。

## 2. 自动化测试结果

| 检查 | 命令/范围 | 结果 |
| --- | --- | --- |
| 定向后端测试 | `mvn ... -Dtest=PhotoProcessingAsyncConfigTests,ImageCompressorTests,BatchUploadServiceTests,PhotoProcessingFilePipelineTests,PreviewRegenerationServiceTests,PreviewRegenerationCoordinatorTests` | 23 个测试通过，0 失败，0 错误 |
| 后端全量测试 | `backend\\mvnw.cmd test` | 197 个测试，0 失败，0 错误，1 跳过 |
| 从零完整打包 | `backend\\mvnw.cmd clean package` | 成功；再次运行 197 个测试，0 失败，0 错误，1 跳过 |
| 主前端 lint | `npx eslint src` | 通过 |
| 主前端生产构建 | Maven `npm run build -- --base=/` | TypeScript 与 Vite 构建通过，npm 审计 0 个漏洞 |
| Git 差异检查 | `git diff --cached --check` | 通过 |
| 堆内存模式扫描 | 图片生产代码扫描 `readAllBytes`、`ByteArrayOutputStream` | 均无命中 |
| 旧原生 ABI 扫描 | 扫描 `photolib_process`、`photolib_dimensions`、`photolib_free` | 旧内存指针接口均无命中 |
| Fat JAR 内容 | 检查 ZIP 条目 | 同时包含 Linux `.so`、Windows `.dll` 和 `static/index.html` |

全量测试中跳过的是 `AliyunObjectStorageServiceIntegrationTests`。该测试按项目约定只在显式提供真实 OSS 凭据时运行；本次未连接真实 Bucket。

## 3. 关键覆盖场景

### 3.1 线程池与配置

- 配置为 2 个工作线程时，两项任务能够同时进入执行态，且线程名均为 `photo-processing-*`。
- 0 和 33 会在属性绑定阶段被拒绝，避免无界或不合理并发。
- ZIP 解压协调器保持单线程；预览重建仍由独立单线程协调，但每次 Zig 操作必须进入共享图片处理池。

### 3.2 原生文件接口

- JPEG、PNG、透明 PNG、低于目标体积的文件复制、缩略图、24 MP 大图和尺寸/像素上限均通过。
- Windows 中文及 Unicode 路径的读写通过。
- 原生输出长度会与磁盘实际文件大小二次核对。
- Windows x86-64 与 Linux x86-64 组件均从干净构建目录成功编译。

### 3.3 ZIP 磁盘管线

- ZIP 中 JPEG/PNG 条目解压后形成受管本地文件，数据库保存内部路径，但序列化字段带 `@JsonIgnore`。
- 解压后的图片不会先写入对象存储；原 ZIP 对象在解压完成后删除，并显式清空数据库 `archive_object_key`。
- 空包、非法路径、图片数量、单图大小和展开总量沿用原有限制。
- ZIP 网络读取和磁盘解压阶段没有活动数据库事务；运行时防护会拒绝从外层事务直接调用该阶段。
- 全部条目解压成功后，使用 `TransactionTemplate` 在一个短事务中原子插入最多 100 条记录并切换批次状态。
- 任一条记录落库失败时短事务整体回滚，数据库不会留下部分条目，已经产生的本地文件会全部删除。
- 失败状态、原 ZIP 对象删除后的键清理分别使用独立短事务；若失败状态无法写回数据库，则保留原 ZIP 对象供排查或重试。

### 3.4 端到端图片处理

新增 Spring 集成测试使用真实 JPEG、本地磁盘存储、数据库批次与共享原生线程池，验证：

- ZIP 本地源文件被 Zig 读取并生成成品图与预览图；
- Java 流式上传原图、成品图和预览图；
- 图片状态转为 `AVAILABLE`，尺寸、文件大小、预览大小和原图到期时间正确；
- 批次与条目转为 `SUCCEEDED`；
- `temp_local_path` 被显式清空；
- ZIP 源文件及任务输入/输出辅助文件最终全部删除。

## 4. 构建产物核验

最终 JAR：`backend/target/photolib-backend-0.1.0-SNAPSHOT.jar`，大小 68,206,230 字节。

确认包含：

- `BOOT-INF/classes/native/windows-x86_64/photolib-image.dll`（1,165,824 字节）
- `BOOT-INF/classes/native/linux-x86_64/libphotolib-image.so`（1,120,224 字节）
- `BOOT-INF/classes/static/index.html`

## 5. 已知基线与未执行项

- 全仓 `npm run lint` 仍失败，错误全部位于本次未修改的旧库 `photowarehouse/FrontedPhotoWare/src/App.tsx`：6 个 `no-explicit-any` 错误和 7 个 Hook 依赖警告。主应用 `src/` 定向 lint 已通过。
- Vite 继续报告既有的单个大于 500 kB chunk 警告，不影响构建成功。
- JDK 25 对 JNA/Mockito 动态加载给出未来版本兼容性警告，不影响当前测试；项目目标运行版本仍为 Java 21。
- 未运行需要真实 MySQL、真实 OSS、真实邮件配置和隔离服务的 `qa_full_flow.ps1`，以避免误连生产资源。
- 本轮验证了 24 MP 图片与双任务并行正确性，但没有把并发数提高到生产服务器尚未评估的值，也没有以生产图库进行长时间容量压测。上线时建议从默认 `PHOTO_PROCESSING_THREADS=1` 开始，依据进程 RSS、磁盘空间和真实大图峰值逐步调整。

## 6. 结论

实现与自动化测试表明，ZIP 条目不再整块进入 Java 堆，ZIP 网络/磁盘 I/O 也不会持有长数据库事务，Java 与 Zig 之间不再复制整张图片字节数组。Zig 原生内存并发由统一、可配置的固定线程池约束；本地辅助文件在处理完成后清理。除上述明确记录的外部集成与生产容量压测外，本次改造通过了项目完整测试和最终打包验证。
