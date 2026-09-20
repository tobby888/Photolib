# PhotoLib MCP 服务

把 PhotoLib 的能力开放给 AI 客户端（Claude Desktop、Claude Code、Cursor 等支持
[MCP](https://modelcontextprotocol.io) 的宿主）：选题、图片需求、图库与上传、采用、
工时、统计与导出、站内消息、通讯录、好图精选、文档中心、成员招募、分享链接和系统管理。

用 Python + [FastMCP](https://github.com/jlowin/fastmcp) 写成，以 stdio 运行。

## 登录不用密码

MCP 服务跑在你自己的机器上，宿主只会把配置文件里的环境变量交给它。**这里不接受账号
密码**：密码写进配置文件就意味着它会被同步、被截图、被贴进群里，而且一旦泄漏丢的是整个
账号，不是一个可以单独吊销的会话。

取而代之的是浏览器登录（设备码模式）：

1. AI 调用 `photolib_login`，终端/对话里出现一条站内链接和一串配对码（形如 `ABCD-EFGH`）；
2. 你在浏览器里打开那条链接（多半会自动弹出来），用平时那个已登录的会话看到
   "哪个客户端、哪台机器、什么时候发起的"；
3. **手动输入**终端上那串配对码，点批准；
4. 客户端换到一对令牌，存在本机 `~/.photolib-mcp/credentials.json`（权限 0600）。

配对码必须手敲，这一步不能省：只凭链接就能批准的话，别人把他自己的配对链接发给你、你
顺手一点，他就拿到了一个以你的身份说话的令牌。配对码只出现在发起配对的那个终端上，所以
"手上有码"约等于"这台机器就是我自己的"。后端也只存配对码的哈希，猜错 5 次该次配对直接
作废，配对请求 10 分钟过期。服务端实现见
`backend/src/main/java/cn/photolib/mcp/McpAuthorizationService.java`。

拿到的令牌就是一次**普通登录**签发的会话：权限、校区范围、改密或停用后的失效全都和网页
一致。**MCP 不是一个新的权限边界**，它做不到你本人在网页上做不到的事。

要收回授权：调用 `photolib_logout`、跑 `photolib-mcp logout`，或者直接改密码
（改密会让所有会话失效）。

## 安装

需要 Python 3.10+。

```bash
cd mcp
pip install -e .
```

先把登录走一遍（也可以跳过，让 AI 在第一次用到时调 `photolib_login`）：

```bash
PHOTOLIB_BASE_URL=https://photowarehouse.cn photolib-mcp login
```

然后在宿主里登记这个服务。Claude Code：

```bash
claude mcp add photolib --env PHOTOLIB_BASE_URL=https://photowarehouse.cn -- photolib-mcp
```

Claude Desktop（`claude_desktop_config.json`）、Cursor 等用配置文件的宿主：

```json
{
  "mcpServers": {
    "photolib": {
      "command": "photolib-mcp",
      "env": { "PHOTOLIB_BASE_URL": "https://photowarehouse.cn" }
    }
  }
}
```

没装到 PATH 上时把 `command` 换成 `python`、`args` 写
`["-m", "photolib_mcp", "--", "serve"]`，并确保 `cwd` 或 `PYTHONPATH` 指到 `mcp/` 目录。

装完自检：

```bash
photolib-mcp doctor
```

它会打印站点地址、凭据文件位置、启用的工具分组、工具数量、后端连通性和登录状态——
宿主里连不上时，先看这一行输出，比翻宿主日志快。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PHOTOLIB_BASE_URL` | `http://localhost:8080` | 站点地址，**不含** `/api/v1`。不写协议头按 https 补全 |
| `PHOTOLIB_MCP_CREDENTIALS` | `~/.photolib-mcp/credentials.json` | 凭据文件位置 |
| `PHOTOLIB_MCP_CLIENT_NAME` | `PhotoLib MCP` | 批准页上显示的客户端名字 |
| `PHOTOLIB_MCP_DEVICE_LABEL` | 本机主机名 | 批准页上显示的设备名 |
| `PHOTOLIB_MCP_TOOLSETS` | 全部 | 只启用部分分组，逗号分隔。开哪些见[收窄工具链](#收窄工具链开哪些改哪个变量什么时候关) |
| `PHOTOLIB_MCP_READ_ONLY` | `false` | 只读模式：所有写操作工具都不注册。**登录和导出也算写操作**，见[只读模式的代价](#只读模式的代价) |
| `PHOTOLIB_MCP_OPEN_BROWSER` | `true` | 登录时是否自动拉起浏览器（无桌面的机器上置 false） |
| `PHOTOLIB_MCP_TIMEOUT` | `60` | 普通接口超时（秒） |
| `PHOTOLIB_MCP_TRANSFER_TIMEOUT` | `600` | 上传/下载超时（秒） |

## 工具

一共 195 个工具（122 个写、73 个读），按分组划分。默认全开——"这个 MCP 要包含项目的所有能力"是它存在的前提；嫌多就按下一节收窄：

| 分组 | 大致内容 |
| --- | --- |
| `auth` | 登录、登录状态、当前身份、改密、系统枚举与限额 |
| `projects` | 选题增删改查、状态流转、相册、选片人与选片台 |
| `requests` | 图片需求发布/批量发布、接单、交付、打回、取消 |
| `photos` | 图库检索、单张/批量/ZIP 上传、元数据、标签、收藏、归档、下载、打包 |
| `adoptions` | 采用标记与采用排行 |
| `worklogs` | 工时填报、提交、确认、驳回 |
| `statistics` | 总览、成员统计、工时明细、导出任务 |
| `notifications` | 站内信收发、未读数、外发投递记录 |
| `directory` | 通讯录、校区、负责人校区指派 |
| `featured` | 好图精选征集、条目填报、成稿文档 |
| `docs` | 文档中心编辑端与阅读端 |
| `recruitment` | 招募任务、报名查看与导出、对外报名通道 |
| `shares` | 分享链接管理端 + 访客端（浏览、下载、标记被引、上传） |
| `admin` | 账号、权限组、审计日志、告警、品牌、数据库备份与回滚 |
| `misc` | 说明配图、消息配图、头像、首次改密、选片改图、通用接口出口 |

## 收窄工具链：开哪些、改哪个变量、什么时候关

默认全开的 195 个工具会占掉宿主相当一部分上下文，有些宿主还有工具数量上限。**服务本身
不会替你收窄**——它不知道你是谁、这台机器给谁用、这次要干什么。下面把所有判断依据摊开，
开哪些、什么时候关，由你自己决定，也由你自己动手生效。

先说清楚一件事：这两个开关管的是**上下文占用和误操作面**，不是权限。权限永远由后端按你
的账号判定，关掉 `admin` 分组不会让你不再是管理员，开着它也不会让你多出一项权限。真要
限制某个人能做什么，去改他的权限组，别指望改 MCP 配置。

### 两个开关，改在宿主的配置里

| 想要的效果 | 改哪个环境变量 | 写法 |
| --- | --- | --- |
| 只注册用得上的分组 | `PHOTOLIB_MCP_TOOLSETS` | 逗号分隔，如 `auth,projects,photos`；**不写 = 全开** |
| 一个写工具都不注册 | `PHOTOLIB_MCP_READ_ONLY` | `true` / `false`（默认 `false`） |

分组名写错服务会直接启动失败，并在报错里列出可选值——宿主里看到 photolib 起不来，先看这行。

Claude Code：

```bash
claude mcp remove photolib
claude mcp add photolib \
  --env PHOTOLIB_BASE_URL=https://photowarehouse.cn \
  --env PHOTOLIB_MCP_TOOLSETS=auth,projects,requests,photos,worklogs,directory \
  -- photolib-mcp
```

Claude Desktop / Cursor 等配置文件宿主，改 `env` 里的那几行：

```json
{
  "mcpServers": {
    "photolib": {
      "command": "photolib-mcp",
      "env": {
        "PHOTOLIB_BASE_URL": "https://photowarehouse.cn",
        "PHOTOLIB_MCP_TOOLSETS": "auth,projects,requests,photos,worklogs,directory",
        "PHOTOLIB_MCP_READ_ONLY": "false"
      }
    }
  }
}
```

**改完要重启宿主**，它只在拉起服务时读一次 env。改完跑一次 `photolib-mcp doctor`
核对"启用分组 / 只读模式 / 已注册工具 N 个"这三行——数字和你预期对不上，多半是宿主还
在用旧进程。

### 分组该在什么时候打开

| 分组 | 工具数（只读下剩） | 什么时候打开 | 关掉之后 AI 就做不了 |
| --- | --- | --- | --- |
| `auth` | 6（2） | **基本别关**，见下一节 | 登录、查当前身份、取系统枚举 |
| `projects` | 16（7） | 要建/改选题、走状态流转、管相册和选片台 | 一切选题相关 |
| `requests` | 16（5） | 要发布图片需求、接单、交付、打回 | 一切需求相关 |
| `photos` | 18（4） | 要传图、检索图库、改元数据、下载打包 | 上传和下载 |
| `adoptions` | 4（2） | 要标记采用、看采用排行 | 采用标记 |
| `worklogs` | 7（1） | 要填报/审核工时 | 工时填报 |
| `statistics` | 7（4） | 要看总览、成员统计，或跑导出（期末结算常用） | 统计与导出 |
| `notifications` | 8（5） | 要收发站内信、查未读、看外发投递记录 | 站内信 |
| `directory` | 12（5） | 要查通讯录、管校区；**传图和填工时也依赖它** | 按人名找 id |
| `featured` | 14（5） | 好图精选征集期间 | 精选征集 |
| `docs` | 12（4） | 要编辑或阅读文档中心 | 文档中心 |
| `recruitment` | 14（6） | 招募季：发任务、看报名、导出名单 | 招募相关 |
| `shares` | 19（8） | 要发分享链接，或用访客端验收链接效果 | 分享链接 |
| `admin` | 34（14） | 管账号、权限组、审计日志、品牌、数据库备份与回滚 | 后台管理（**也就少了一整块高风险操作**） |
| `misc` | 8（1） | 要传说明配图/消息配图/头像，或需要通用接口出口 | `photolib_api_request` 这个万能出口 |

### 几条硬依赖，收窄前先看

- **`auth` 基本不能关。** `photolib_login`、`photolib_whoami`、`photolib_metadata_options`
  都在里面。关掉之后没有任何工具能发起登录，只能先在终端跑 `photolib-mcp login`。
- **开 `photos` 或 `worklogs` 就要开 `directory`。** 上传要 `photographer_contact_id`、
  工时要 `member_contact_id`，都是通讯录成员 id，只能用 `photolib_directory_members_list`
  查；这两个字段不接受随便填的名字。
- **`projects_add_photos` 要配 `photos`** 才找得到图片 id。
- **`statistics` 自带导出闭环**（建任务 → 查进度 → 下载），单开它就能跑完一次导出。
- **开着 `misc` 的话，分组收窄只是省上下文。** `photolib_api_request` 能直接打任意
  `/api/v1` 接口，模型绕过分组限制没有任何难度（它仍然受后端权限约束）。真想把能力面
  收住，就别开 `misc`，或者同时开只读。

### 只读模式的代价

`PHOTOLIB_MCP_READ_ONLY=true` 按"会不会改数据"划线，写工具**根本不会注册**——模型看不见
的工具才不会反复去试。全开时 195 个工具会剩 73 个。但有两类东西按这条线一起没了，很容易
踩：

- **登录也没了。** `photolib_login` / `login_status` / `logout` / `change_password` 都算写
  操作。只读模式下要**先在终端 `photolib-mcp login` 登录好**，再让宿主起服务；否则每个工具
  都会提示你去调一个不存在的登录工具。
- **导出和下载也没了。** 它们在后端都是 POST 建任务：`statistics_export`、`worklogs_export`、
  `export_job_download`、`audit_logs_export`、`photos_download` / `photos_download_url` /
  `photos_batch_download`、`recruitment_applications_export`、`docs_public_download_file`。
  想要"只让 AI 查数据、但还能导出报表"，**别用只读**，改用分组收窄（例如只开
  `auth,statistics,directory`），再在对话里要求它导出前先说明。
- `photolib_api_request` 在只读下会拒绝 POST/PUT/PATCH/DELETE。

### 几套现成配方

照抄或者改，右边是实际注册的工具数：

| 场景 | `PHOTOLIB_MCP_TOOLSETS` | 工具数 |
| --- | --- | --- |
| 日常拍摄组（选题/需求/图库/工时） | `auth,projects,requests,photos,worklogs,directory` | 75 |
| 日常 + 统计与站内信 | `auth,projects,requests,photos,worklogs,statistics,directory,notifications` | 90 |
| 只做统计导出、期末结算 | `auth,statistics,directory` | 25 |
| 只整理图库 | `auth,photos,directory,projects` | 52 |
| 管理员维护窗口（用完关回去） | `auth,admin` | 40 |
| 发分享链接、验收访客端 | `auth,shares` | 25 |
| 文档中心 | `auth,docs` | 18 |
| 好图精选征集期 | `auth,featured,directory` | 32 |
| 招募季 | `auth,recruitment` | 20 |
| 只读旁观（记得先终端登录） | 不写，配 `PHOTOLIB_MCP_READ_ONLY=true` | 73 |

第一次接入建议先全开跑通一遍，确认登录和常用流程都正常，再按上表收窄——一上来就收窄，
出问题时分不清是配置错了还是没装好。

### 什么时候应该关掉

这些都是建议，关不关、什么时候关由你定：

- **宿主提示工具数超限、或者对话明显变慢变笨** → 先关那些偶尔才用的：`admin`、`shares`、
  `recruitment`、`featured`、`docs`。
- **高风险操作做完了** → 把 `admin` 关回去。数据库备份与回滚、权限组调整、重置密码都在这一组，
  它更适合"临时开、用完关"，而不是长期挂着。
- **要演示、录屏、投屏给别人看** → 至少关掉 `directory`（通讯录里是真实姓名和联系方式）和
  `admin`（审计日志、账号列表）。
- **机器要借给别人、或者是公用电脑** → 跑 `photolib-mcp logout`，或者在宿主里停用 photolib
  这个服务。凭据留在本机 `~/.photolib-mcp/credentials.json`，谁用这台机器谁就是以你的身份说话。
- **离职、换岗、或者怀疑令牌泄漏** → `photolib_logout` / `photolib-mcp logout`，或者直接改密码
  （改密会让所有会话一起失效）。
- **不再用 AI 接入** → 宿主配置里删掉 photolib 这条，再 logout 清掉本机凭据。

### 通用出口

`photolib_api_request`（在 `misc` 分组里）可以直接调任意 `/api/v1` 接口，用于工具没覆盖到的
角落。它不做参数校验，能力仍由后端权限判定；只读模式下它会拒绝写方法。优先用具体的工具——
它们带着参数校验、上传流程和用法说明。

## 开发

```bash
pip install -e ".[dev]"
pytest
```

测试全部用 `httpx.MockTransport` 打桩，不连任何真实后端；凭据路径也被 fixture 指到临时
目录，不会碰到你自己的 `~/.photolib-mcp/credentials.json`。

代码分层：

- `config.py` 环境变量 → `Settings`
- `credentials.py` 本机凭据文件（0600，Windows 上另外收 ACL）
- `session.py` 一条带自动续期的 HTTP 会话：拼地址、带令牌、拆信封、401 续一次再重试
- `device_login.py` 浏览器登录（发起配对 → 打开链接 → 轮询 → 落盘）
- `transfers.py` 本地文件的上传下载（按魔数判类型、直传预签名地址）
- `toolkit.py` 工具注册（统一前缀、只读模式跳过写工具）
- `tools/*.py` 每个模块一个 `register(registry)`
- `server.py` 组装 FastMCP 实例
