"""好图精选：征集、填报与成稿文档。"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params
from ..transfers import download_to_path

CollectionStatus = Literal["DRAFT", "PUBLISHED", "CLOSED"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("featured_list", tags=("featured",))
    async def featured_list(page: int = 1, page_size: int = 20, keyword: str | None = None,
                            status: CollectionStatus | None = None) -> dict[str, Any]:
        """分页查询好图精选征集。"""
        return await session.request("GET", "/featured-collections", params=page_params(
            page, page_size, keyword=keyword, status=status))

    @registry.tool("featured_get", tags=("featured",))
    async def featured_get(collection_id: int) -> dict[str, Any]:
        """一次征集的详情。"""
        return await session.request("GET", f"/featured-collections/{collection_id}")

    @registry.tool("featured_entries", tags=("featured",))
    async def featured_entries(collection_id: int) -> list[dict[str, Any]]:
        """一次征集下已提交的条目。"""
        return await session.request("GET", f"/featured-collections/{collection_id}/entries")

    @registry.tool("featured_assignable_managers", tags=("featured",))
    async def featured_assignable_managers() -> list[dict[str, Any]]:
        """可以被指派填报的负责人（需要 FEATURED_MANAGE 权限）。"""
        return await session.request("GET", "/featured-collections/assignable-managers")

    @registry.tool("featured_create", write=True, tags=("featured",))
    async def featured_create(title: str, starts_at: str, ends_at: str, entry_limit: int,
                              requirement_html: str | None = None, assign_all: bool = False,
                              campus_ids: list[int] | None = None,
                              user_ids: list[int] | None = None) -> dict[str, Any]:
        """新建一次征集（需要 FEATURED_MANAGE 权限）。

        时间用 ISO 本地时间（`2026-10-01T09:00:00`）。`entry_limit` 是**每人**的投稿上限
        （1~50）。`assign_all` 为 true 表示指派所有负责人，否则按 campus_ids / user_ids 指派。
        新建出来是草稿，要 photolib_featured_publish 之后别人才看得到。
        """
        return await session.request("POST", "/featured-collections", json_body=compact({
            "title": title, "requirementHtml": requirement_html,
            "startsAt": starts_at, "endsAt": ends_at, "assignAll": assign_all,
            "entryLimit": entry_limit, "campusIds": campus_ids, "userIds": user_ids,
        }))

    @registry.tool("featured_update", write=True, tags=("featured",))
    async def featured_update(collection_id: int, title: str, starts_at: str, ends_at: str,
                              entry_limit: int, version: int,
                              requirement_html: str | None = None, assign_all: bool = False,
                              campus_ids: list[int] | None = None,
                              user_ids: list[int] | None = None) -> dict[str, Any]:
        """修改征集。这是全量覆盖，指派名单也会被整体替换。"""
        return await session.request(
            "PUT", f"/featured-collections/{collection_id}", json_body=compact({
                "title": title, "requirementHtml": requirement_html,
                "startsAt": starts_at, "endsAt": ends_at, "assignAll": assign_all,
                "entryLimit": entry_limit, "campusIds": campus_ids, "userIds": user_ids,
                "version": version,
            }))

    @registry.tool("featured_publish", write=True, tags=("featured",))
    async def featured_publish(collection_id: int, version: int) -> dict[str, Any]:
        """发布征集，被指派的负责人就能开始填报了。"""
        return await session.request("POST", f"/featured-collections/{collection_id}/publish",
                                     json_body={"version": version})

    @registry.tool("featured_close", write=True, tags=("featured",))
    async def featured_close(collection_id: int) -> dict[str, Any]:
        """手动截止一次征集。截止后不能再提交条目。"""
        return await session.request("POST", f"/featured-collections/{collection_id}/close")

    @registry.tool("featured_delete", write=True, tags=("featured",))
    async def featured_delete(collection_id: int, version: int) -> dict[str, Any]:
        """删除一次征集。"""
        await session.request("DELETE", f"/featured-collections/{collection_id}",
                              params={"version": version})
        return {"status": "DELETED", "collectionId": collection_id}

    @registry.tool("featured_add_entry", write=True, tags=("featured",))
    async def featured_add_entry(collection_id: int, photo_id: int, idea: str,
                                 location: str) -> dict[str, Any]:
        """提交一条精选条目：选一张图，写创作思路和拍摄地点。

        只能选自己能看到的图片，且每人有投稿上限；开始前和截止后都提交不了。
        """
        return await session.request(
            "POST", f"/featured-collections/{collection_id}/entries",
            json_body={"photoId": photo_id, "idea": idea, "location": location})

    @registry.tool("featured_update_entry", write=True, tags=("featured",))
    async def featured_update_entry(collection_id: int, entry_id: int, photo_id: int, idea: str,
                                    location: str, version: int) -> dict[str, Any]:
        """修改自己的一条精选条目。"""
        return await session.request(
            "PUT", f"/featured-collections/{collection_id}/entries/{entry_id}",
            json_body={"photoId": photo_id, "idea": idea, "location": location,
                       "version": version})

    @registry.tool("featured_delete_entry", write=True, tags=("featured",))
    async def featured_delete_entry(collection_id: int, entry_id: int) -> dict[str, Any]:
        """撤下一条精选条目。撤下后同一张图可以重新加入。"""
        await session.request("DELETE", f"/featured-collections/{collection_id}/entries/{entry_id}")
        return {"status": "DELETED", "entryId": entry_id}

    @registry.tool("featured_document", tags=("featured",))
    async def featured_document(collection_id: int,
                                save_to: str | None = None) -> dict[str, Any]:
        """取征集成稿 Word 文档的下载地址；给了 `save_to` 就直接存到本地。"""
        link = await session.request("GET", f"/featured-collections/{collection_id}/document")
        if not save_to:
            return link
        saved = await download_to_path(
            session, link["downloadUrl"], save_to,
            default_name=link.get("fileName") or f"featured-{collection_id}.docx")
        return {"saved": saved, "link": link}

    @registry.tool("featured_regenerate_document", write=True, tags=("featured",))
    async def featured_regenerate_document(collection_id: int) -> dict[str, Any]:
        """重新生成征集的成稿文档（条目改过之后用）。"""
        return await session.request("POST", f"/featured-collections/{collection_id}/document")
