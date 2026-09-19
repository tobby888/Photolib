"""选题：建立、流转、相册、选片人与选片台。"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params

ProjectStatus = Literal["DRAFT", "ACTIVE", "COMPLETED", "CANCELLED"]
ProjectType = Literal["CREATION", "EVENT"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("projects_list", tags=("projects",))
    async def projects_list(page: int = 1, page_size: int = 20, keyword: str | None = None,
                            status: ProjectStatus | None = None) -> dict[str, Any]:
        """分页查询选题。

        看到的范围取决于账号权限：只有 PROJECT_VIEW 的成员只看得到自己接过需求的选题，
        PROJECT_VIEW_ALL 才看得到全部。
        """
        return await session.request("GET", "/projects", params=page_params(
            page, page_size, keyword=keyword, status=status))

    @registry.tool("projects_get", tags=("projects",))
    async def projects_get(project_id: int) -> dict[str, Any]:
        """选题详情，含需求、相册和采用情况。"""
        return await session.request("GET", f"/projects/{project_id}")

    @registry.tool("projects_create", write=True, tags=("projects",))
    async def projects_create(title: str, status: ProjectStatus, description: str | None = None,
                              project_type: ProjectType | None = None,
                              tags: list[str] | None = None) -> dict[str, Any]:
        """新建选题。

        `project_type` 省略时按创作选题（CREATION）建立，**建立后不可修改**；
        活动选题（EVENT）才有选片台和上传链接。`tags` 是这个选题的预设标签，
        之后给图片打标签会受它约束。
        """
        return await session.request("POST", "/projects", json_body=compact({
            "title": title, "description": description, "status": status,
            "type": project_type, "tags": tags,
        }))

    @registry.tool("projects_update", write=True, tags=("projects",))
    async def projects_update(project_id: int, title: str, version: int,
                              description: str | None = None,
                              tags: list[str] | None = None) -> dict[str, Any]:
        """修改选题的标题、说明和预设标签。

        `version` 是乐观锁，取自 projects_get 的返回；版本对不上说明别人刚改过，
        应当重新读一遍再改，不要盲目重试。`tags` 省略表示保留原预设标签，传空数组
        表示取消标签限制。
        """
        return await session.request("PUT", f"/projects/{project_id}", json_body=compact({
            "title": title, "description": description, "tags": tags, "version": version,
        }))

    @registry.tool("projects_change_status", write=True, tags=("projects",))
    async def projects_change_status(project_id: int, status: ProjectStatus,
                                     version: int) -> dict[str, Any]:
        """流转选题状态（草稿 / 进行中 / 已完成 / 已取消）。"""
        return await session.request("POST", f"/projects/{project_id}/status",
                                     json_body={"status": status, "version": version})

    @registry.tool("projects_reopen", write=True, tags=("projects",))
    async def projects_reopen(project_id: int, reason: str, version: int) -> dict[str, Any]:
        """把已完成的选题重新打开（仅管理员）。reason 会进审计日志。"""
        return await session.request("POST", f"/projects/{project_id}/reopen",
                                     json_body={"reason": reason, "version": version})

    @registry.tool("projects_delete", write=True, tags=("projects",))
    async def projects_delete(project_id: int) -> dict[str, Any]:
        """删除选题（软删除）。"""
        await session.request("DELETE", f"/projects/{project_id}")
        return {"status": "DELETED", "projectId": project_id}

    @registry.tool("projects_add_photos", write=True, tags=("projects",))
    async def projects_add_photos(project_id: int, photo_ids: list[int]) -> dict[str, Any]:
        """把已有图片加进选题相册（一次最多 200 张）。"""
        await session.request("POST", f"/projects/{project_id}/photos",
                              json_body={"photoIds": photo_ids})
        return {"status": "ADDED", "projectId": project_id, "count": len(photo_ids)}

    # ---- 选片（活动选题）-------------------------------------------------

    @registry.tool("projects_selectors_list", tags=("projects",))
    async def projects_selectors_list(project_id: int) -> list[dict[str, Any]]:
        """这个活动选题当前的选片人。"""
        return await session.request("GET", f"/projects/{project_id}/selectors")

    @registry.tool("projects_selector_candidates", tags=("projects",))
    async def projects_selector_candidates(project_id: int) -> list[dict[str, Any]]:
        """可以被指派为选片人的成员。"""
        return await session.request("GET", f"/projects/{project_id}/selector-candidates")

    @registry.tool("projects_selectors_replace", write=True, tags=("projects",))
    async def projects_selectors_replace(project_id: int, user_ids: list[int]) -> list[dict[str, Any]]:
        """整体替换选片人名单（传空数组表示清空）。"""
        return await session.request("PUT", f"/projects/{project_id}/selectors",
                                     json_body={"userIds": user_ids})

    @registry.tool("projects_selection_photos", tags=("projects",))
    async def projects_selection_photos(project_id: int, page: int = 1,
                                        page_size: int = 60) -> dict[str, Any]:
        """选片台里的待选图片。"""
        return await session.request("GET", f"/projects/{project_id}/selection/photos",
                                     params=page_params(page, page_size))

    @registry.tool("projects_selection_image_url", tags=("projects",))
    async def projects_selection_image_url(project_id: int, photo_id: int) -> dict[str, Any]:
        """取选片台里某张图的临时查看地址（短期有效的签名链接）。"""
        return await session.request(
            "GET", f"/projects/{project_id}/selection/photos/{photo_id}/image-url")

    @registry.tool("projects_selection_tag", write=True, tags=("projects",))
    async def projects_selection_tag(project_id: int, photo_ids: list[int],
                                     add_tags: list[str] | None = None,
                                     remove_tags: list[str] | None = None) -> list[dict[str, Any]]:
        """在选片台里批量增删标签。新增的标签要落在该选题的预设标签范围内。"""
        return await session.request("POST", f"/projects/{project_id}/selection/tags",
                                     json_body=compact({
                                         "photoIds": photo_ids,
                                         "addTags": add_tags, "removeTags": remove_tags,
                                     }))

    @registry.tool("projects_selection_cleanup_plan", tags=("projects",))
    async def projects_selection_cleanup_plan(project_id: int) -> dict[str, Any]:
        """选片收尾前先看清理计划：哪些没被选中的图片会被清掉。

        **务必先看这个再执行清理**，并把将要删除的数量告诉用户、等他确认。
        """
        return await session.request("GET", f"/projects/{project_id}/selection/cleanup")

    @registry.tool("projects_selection_cleanup", write=True, tags=("projects",))
    async def projects_selection_cleanup(project_id: int) -> dict[str, Any]:
        """执行选片收尾清理：删除未入选的图片。

        这是不可逆的批量删除。调用前请先用 photolib_projects_selection_cleanup_plan
        看清楚影响范围，并取得用户的明确确认。
        """
        return await session.request("POST", f"/projects/{project_id}/selection/cleanup")
