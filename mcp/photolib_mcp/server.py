"""FastMCP 服务的组装。"""

from __future__ import annotations

from typing import Callable

from fastmcp import FastMCP

from .config import Settings, load_settings
from .session import PhotoLibSession
from .toolkit import ToolRegistry
from .tools import (
    admin, adoptions, auth, directory, docs, featured, misc, notifications,
    photo_requests, photos, projects, recruitment, shares, statistics, worklogs,
)

#: 分组名 → 注册函数。键必须和 `config.ALL_TOOLSETS` 一致，
#: `tests/test_server.py` 会盯着这一点。
TOOLSETS: dict[str, Callable[[ToolRegistry], None]] = {
    "auth": auth.register,
    "projects": projects.register,
    "requests": photo_requests.register,
    "photos": photos.register,
    "adoptions": adoptions.register,
    "worklogs": worklogs.register,
    "statistics": statistics.register,
    "notifications": notifications.register,
    "directory": directory.register,
    "featured": featured.register,
    "docs": docs.register,
    "recruitment": recruitment.register,
    "shares": shares.register,
    "admin": admin.register,
    "misc": misc.register,
}

INSTRUCTIONS = """\
PhotoLib 摄影工作台的 MCP 服务：选题、图片需求、图库、采用、工时、统计导出、
站内消息、通讯录、好图精选、文档中心、成员招募、分享链接和系统管理。

几条贯穿全部工具的约定：

1. 先登录。没有凭据时任何工具都会提示调用 `photolib_login`；它会给出一条浏览器
   链接和一串配对码，**配对码必须由用户本人在浏览器里手敲**，不要替他绕过，
   也不要把链接转给别人。
2. 权限在后端。看不到或做不了某件事，就是这个账号没有那项权限，
   不要换个工具绕。用 `photolib_whoami` 看当前身份和权限。
3. 改之前先读。带 `version` 参数的接口是乐观锁：先读一遍拿到当前 version 再改；
   报版本冲突说明别人刚改过，应当重新读取而不是盲目重试。
4. 全量覆盖的更新要小心。多数 update 工具是整体替换（权限清单、指派名单、
   登录页文案等），没传的字段会被清空。
5. 影响面大的操作要先确认。批量删除、选片收尾清理、重置密码、改权限组、
   群发站内信、建分享链接、数据库回滚——执行前把影响范围复述给用户并等他确认。
6. 时间用 Asia/Shanghai 的本地时间：日期是 `YYYY-MM-DD`，时刻是 `2026-09-01T14:30:00`。
7. 拍摄者和工时成员只能来自通讯录，用 `photolib_directory_members_list` 取 id。
"""


def build_server(settings: Settings | None = None) -> FastMCP:
    settings = settings or load_settings()
    session = PhotoLibSession(settings)
    mcp = FastMCP(name="photolib", instructions=INSTRUCTIONS)
    registry = ToolRegistry(mcp, session, read_only=settings.read_only)

    for name in settings.toolsets:
        TOOLSETS[name](registry)

    # 挂在实例上，给测试和 `__main__` 里的自检用。
    mcp.photolib_session = session  # type: ignore[attr-defined]
    mcp.photolib_registry = registry  # type: ignore[attr-defined]
    return mcp


def main() -> None:
    build_server().run()
