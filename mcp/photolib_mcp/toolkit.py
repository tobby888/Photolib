"""工具注册的公共部分。

每个 `tools/*.py` 拿到一个 :class:`ToolRegistry`，用 `@registry.tool(...)` 挂工具。
注册器只负责三件事：统一前缀、只读模式下跳过写工具、把会话递给工具函数。
"""

from __future__ import annotations

from typing import Any, Callable, Iterable, Mapping

from .session import PhotoLibSession

#: 所有工具的统一前缀。宿主里往往同时接着好几个 MCP，`list`、`create` 这种名字
#: 撞车是迟早的事，撞上之后模型挑错工具的代价比名字长几个字符大得多。
PREFIX = "photolib"


class ToolRegistry:
    def __init__(self, mcp: Any, session: PhotoLibSession, *, read_only: bool = False) -> None:
        self.mcp = mcp
        self.session = session
        self.read_only = read_only
        self.registered: list[str] = []
        self.skipped: list[str] = []

    def tool(self, name: str, *, write: bool = False, tags: Iterable[str] = ()) -> Callable:
        """挂一个工具。

        :param name: 不含前缀的名字，例如 ``projects_list``。
        :param write: 是否会改动数据。只读模式下这类工具**不会被注册**——
            不是注册了再拒绝：模型看不见的工具才不会去试，看得见却每次都失败的
            工具只会让它反复重试。
        """
        full_name = f"{PREFIX}_{name}"

        def decorator(fn: Callable) -> Callable:
            if write and self.read_only:
                self.skipped.append(full_name)
                return fn
            self.registered.append(full_name)
            return self.mcp.tool(name=full_name, tags=set(tags) if tags else None)(fn)

        return decorator


def page_params(page: int, page_size: int, **extra: Any) -> dict[str, Any]:
    """分页查询参数。值为 None 的筛选项由会话层统一丢掉（见 `session._clean_params`）。"""
    params: dict[str, Any] = {"page": page, "pageSize": page_size}
    params.update(extra)
    return params


def compact(mapping: Mapping[str, Any]) -> dict[str, Any]:
    """去掉值为 None 的字段。

    用在**请求体**上。后端的更新接口多半是全量覆盖：把 ``None`` 原样发过去等于
    "把这个字段清空"，而工具的可选参数没填通常是"这次不动它"。两者只能由调用方
    区分，所以凡是没填的一律不发，需要清空时由工具显式传空值。
    """
    return {key: value for key, value in mapping.items() if value is not None}
