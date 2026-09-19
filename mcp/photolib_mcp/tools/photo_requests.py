"""图片需求：发布、接单、交付与流转。

模块名没有沿用后端的 `request`，是为了不和 Python 的 `requests` 混淆。
"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params

RequestStatus = Literal["DRAFT", "PUBLISHED", "ACCEPTED", "SUBMITTED", "COMPLETED", "CANCELLED"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("requests_list", tags=("requests",))
    async def requests_list(page: int = 1, page_size: int = 20, project_id: int | None = None,
                            status: RequestStatus | None = None, campus_id: int | None = None,
                            participant_id: int | None = None) -> dict[str, Any]:
        """分页查询图片需求。`participant_id` 传自己的用户 id 就是"我接的单"。"""
        return await session.request("GET", "/requests", params=page_params(
            page, page_size, projectId=project_id, status=status,
            campusId=campus_id, participantId=participant_id))

    @registry.tool("requests_get", tags=("requests",))
    async def requests_get(request_id: int) -> dict[str, Any]:
        """需求详情。"""
        return await session.request("GET", f"/requests/{request_id}")

    @registry.tool("requests_create", write=True, tags=("requests",))
    async def requests_create(project_id: int, title: str, campus_id: int, deadline: str,
                              description: str | None = None, required_count: int | None = None,
                              assignee_id: int | None = None) -> dict[str, Any]:
        """在某个选题下发布一条需求。

        `deadline` 用本地时间的 ISO 格式（`2026-10-01T18:00:00`，Asia/Shanghai），
        **必须是将来的时间**。`assignee_id` 指定谁来接单，留空则任何符合条件的成员都能接。
        """
        return await session.request(f"POST", f"/projects/{project_id}/requests", json_body=compact({
            "title": title, "description": description, "campusId": campus_id,
            "requiredCount": required_count, "deadline": deadline, "assigneeId": assignee_id,
        }))

    @registry.tool("requests_batch_publish", write=True, tags=("requests",))
    async def requests_batch_publish(project_id: int, title: str, campus_ids: list[int],
                                     deadline: str, description: str | None = None,
                                     required_count: int | None = None,
                                     assignee_id: int | None = None,
                                     batch_id: str | None = None) -> list[dict[str, Any]]:
        """一次给多个校区发同一条需求，每个校区各生成一条。

        返回里逐校区给出成功或失败。**部分失败时不要整批重发**：把第一次返回的
        `batchId` 原样传进来重试，只补没成功的那些。
        """
        return await session.request(
            "POST", f"/projects/{project_id}/requests/batch-publish", json_body=compact({
                "title": title, "description": description, "campusIds": campus_ids,
                "requiredCount": required_count, "deadline": deadline,
                "assigneeId": assignee_id, "batchId": batch_id,
            }))

    @registry.tool("requests_assignable_users", tags=("requests",))
    async def requests_assignable_users(campus_ids: list[int]) -> list[dict[str, Any]]:
        """这些校区里可以被指派接单的成员。"""
        return await session.request("GET", "/requests/assignable-users",
                                     params={"campusIds": campus_ids})

    @registry.tool("requests_update", write=True, tags=("requests",))
    async def requests_update(request_id: int, title: str, campus_id: int, deadline: str,
                              version: int, description: str | None = None,
                              required_count: int | None = None,
                              assignee_id: int | None = None) -> dict[str, Any]:
        """修改需求。`version` 是乐观锁，取自 requests_get。"""
        return await session.request("PUT", f"/requests/{request_id}", json_body=compact({
            "title": title, "description": description, "campusId": campus_id,
            "requiredCount": required_count, "deadline": deadline,
            "assigneeId": assignee_id, "version": version,
        }))

    @registry.tool("requests_publish", write=True, tags=("requests",))
    async def requests_publish(request_id: int, version: int) -> dict[str, Any]:
        """把草稿需求发布出去，成员才能看到并接单。"""
        return await session.request("POST", f"/requests/{request_id}/publish",
                                     json_body={"version": version})

    @registry.tool("requests_accept", write=True, tags=("requests",))
    async def requests_accept(request_id: int) -> dict[str, Any]:
        """以当前账号接下这条需求。一条需求可以有多位参与人。"""
        return await session.request("POST", f"/requests/{request_id}/accept")

    @registry.tool("requests_participants", tags=("requests",))
    async def requests_participants(request_id: int) -> list[dict[str, Any]]:
        """这条需求的参与人。"""
        return await session.request("GET", f"/requests/{request_id}/participants")

    @registry.tool("requests_leave", write=True, tags=("requests",))
    async def requests_leave(request_id: int) -> dict[str, Any]:
        """当前账号退出这条需求。"""
        await session.request("DELETE", f"/requests/{request_id}/participants/me")
        return {"status": "LEFT", "requestId": request_id}

    @registry.tool("requests_tag_options", tags=("requests",))
    async def requests_tag_options(request_id: int) -> dict[str, Any]:
        """这条需求下上传图片时可用的标签（受所属选题的预设标签约束）。"""
        return await session.request("GET", f"/requests/{request_id}/tag-options")

    @registry.tool("requests_submit", write=True, tags=("requests",))
    async def requests_submit(request_id: int, version: int) -> dict[str, Any]:
        """交付这条需求，等待确认。"""
        return await session.request("POST", f"/requests/{request_id}/submit",
                                     json_body={"version": version})

    @registry.tool("requests_complete", write=True, tags=("requests",))
    async def requests_complete(request_id: int, version: int) -> dict[str, Any]:
        """确认交付，需求完成。"""
        return await session.request("POST", f"/requests/{request_id}/complete",
                                     json_body={"version": version})

    @registry.tool("requests_return", write=True, tags=("requests",))
    async def requests_return(request_id: int, reason: str, version: int) -> dict[str, Any]:
        """打回重做。reason 会通知到参与人。"""
        return await session.request("POST", f"/requests/{request_id}/return",
                                     json_body={"reason": reason, "version": version})

    @registry.tool("requests_cancel", write=True, tags=("requests",))
    async def requests_cancel(request_id: int, reason: str, version: int) -> dict[str, Any]:
        """取消需求。"""
        return await session.request("POST", f"/requests/{request_id}/cancel",
                                     json_body={"reason": reason, "version": version})

    @registry.tool("requests_delete", write=True, tags=("requests",))
    async def requests_delete(request_id: int) -> dict[str, Any]:
        """删除需求（需要 REQUEST_DELETE 权限）。"""
        await session.request("DELETE", f"/requests/{request_id}")
        return {"status": "DELETED", "requestId": request_id}
