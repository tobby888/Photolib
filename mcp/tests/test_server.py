"""服务组装：分组、只读模式、工具命名与关键工具是否都挂上了。"""

from __future__ import annotations

import pytest

from photolib_mcp.config import ALL_TOOLSETS, Settings
from photolib_mcp.server import TOOLSETS, build_server


def test_every_toolset_has_a_registrar() -> None:
    """配置里能写的分组，必须都有对应的注册函数——否则设了等于悄悄少一批工具。"""
    assert set(TOOLSETS) == set(ALL_TOOLSETS)


async def test_default_build_registers_every_module(settings: Settings) -> None:
    server = build_server(settings)
    names = {tool.name for tool in await server.list_tools()}

    # 抽查每个模块的一个代表工具，确认整批都挂上了。
    for expected in [
        "photolib_login", "photolib_whoami",
        "photolib_projects_list", "photolib_requests_list", "photolib_photos_list",
        "photolib_adoptions_ranking", "photolib_worklogs_list", "photolib_statistics_overview",
        "photolib_notifications_list", "photolib_directory_members_list",
        "photolib_featured_list", "photolib_docs_tree", "photolib_recruitment_tasks_list",
        "photolib_shares_list", "photolib_users_list", "photolib_api_request",
    ]:
        assert expected in names, expected


async def test_every_tool_is_prefixed_and_documented(settings: Settings) -> None:
    """名字统一带前缀（宿主里同时接好几个 MCP 时不至于撞车），且每个工具都有说明。

    说明是模型选工具的唯一依据，缺了它那个工具基本等于不存在。
    """
    for tool in await build_server(settings).list_tools():
        assert tool.name.startswith("photolib_"), tool.name
        assert (tool.description or "").strip(), tool.name


async def test_read_only_mode_drops_write_tools(settings: Settings) -> None:
    read_only = build_server(Settings(**{**settings.__dict__, "read_only": True}))
    names = {tool.name for tool in await read_only.list_tools()}

    assert "photolib_projects_list" in names
    assert "photolib_projects_delete" not in names
    assert "photolib_photos_batch_delete" not in names
    assert "photolib_database_backups_restore" not in names
    # 通用出口仍在，但它自己会挡住写方法（见 tools/misc.py 的注释）。
    assert "photolib_api_request" in names


async def test_selected_toolsets_only(settings: Settings) -> None:
    only_photos = build_server(Settings(**{**settings.__dict__, "toolsets": ("photos",)}))
    names = {tool.name for tool in await only_photos.list_tools()}

    assert "photolib_photos_list" in names
    assert "photolib_users_list" not in names


async def test_api_request_refuses_writes_when_read_only(settings: Settings) -> None:
    server = build_server(Settings(**{**settings.__dict__, "read_only": True}))

    with pytest.raises(Exception) as caught:
        await server.call_tool("photolib_api_request", {"method": "DELETE", "path": "/projects/1"})
    assert "只读" in str(caught.value)
