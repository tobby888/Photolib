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
| `PHOTOLIB_MCP_TOOLSETS` | 全部 | 只启用部分分组，逗号分隔 |
| `PHOTOLIB_MCP_READ_ONLY` | `false` | 只读模式：所有写操作工具都不注册 |
| `PHOTOLIB_MCP_OPEN_BROWSER` | `true` | 登录时是否自动拉起浏览器（无桌面的机器上置 false） |
| `PHOTOLIB_MCP_TIMEOUT` | `60` | 普通接口超时（秒） |
| `PHOTOLIB_MCP_TRANSFER_TIMEOUT` | `600` | 上传/下载超时（秒） |

## 工具

一共约 195 个工具，按分组划分：

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

### 工具太多怎么办

195 个工具会占掉宿主相当一部分上下文，有些宿主还有工具数量上限。按实际用途收窄：

```bash
# 只要日常那几块
PHOTOLIB_MCP_TOOLSETS=auth,projects,requests,photos,worklogs,statistics

# 只让 AI 查数据，不让它改
PHOTOLIB_MCP_READ_ONLY=true
```

只读模式下写工具**根本不会注册**（而不是注册了再拒绝）：模型看不见的工具才不会去试。

### 通用出口

`photolib_api_request` 可以直接调任意 `/api/v1` 接口，用于工具没覆盖到的角落。它不做参数
校验，能力仍由后端权限判定；只读模式下它会拒绝写方法。优先用具体的工具。

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
