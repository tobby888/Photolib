"""工时：填报、审核与查询。"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params

WorklogStatus = Literal["DRAFT", "SUBMITTED", "CONFIRMED", "REJECTED"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("worklogs_list", tags=("worklogs",))
    async def worklogs_list(page: int = 1, page_size: int = 20, request_id: int | None = None,
                            user_id: int | None = None, status: WorklogStatus | None = None,
                            date_from: str | None = None,
                            date_to: str | None = None) -> dict[str, Any]:
        """分页查询工时。`date_from` / `date_to` 是 `YYYY-MM-DD`，两端都包含。"""
        return await session.request("GET", "/worklogs", params=page_params(
            page, page_size, requestId=request_id, userId=user_id, status=status,
            **{"from": date_from, "to": date_to}))

    @registry.tool("worklogs_create", write=True, tags=("worklogs",))
    async def worklogs_create(request_id: int, work_date: str, member_contact_id: int,
                              shooting_minutes: int, retouching_minutes: int,
                              status: WorklogStatus = "DRAFT",
                              remark: str | None = None) -> dict[str, Any]:
        """在某条需求下填一条工时。

        - `work_date` 是 `YYYY-MM-DD`，**不能是将来的日期**。
        - `member_contact_id` 是通讯录成员 id（photolib_directory_members_list 查），
          工时算在这个人头上。
        - 拍摄和修图分钟数各自 0~1440。
        - `status` 传 DRAFT 先存草稿，传 SUBMITTED 直接提交审核。
        """
        return await session.request(f"POST", f"/requests/{request_id}/worklogs", json_body=compact({
            "workDate": work_date, "memberContactId": member_contact_id,
            "shootingMinutes": shooting_minutes, "retouchingMinutes": retouching_minutes,
            "remark": remark, "status": status,
        }))

    @registry.tool("worklogs_update", write=True, tags=("worklogs",))
    async def worklogs_update(worklog_id: int, version: int, work_date: str,
                              member_contact_id: int, shooting_minutes: int,
                              retouching_minutes: int, status: WorklogStatus = "DRAFT",
                              remark: str | None = None) -> dict[str, Any]:
        """修改一条工时。已确认的工时改不了。"""
        return await session.request(
            "PUT", f"/worklogs/{worklog_id}", params={"version": version}, json_body=compact({
                "workDate": work_date, "memberContactId": member_contact_id,
                "shootingMinutes": shooting_minutes, "retouchingMinutes": retouching_minutes,
                "remark": remark, "status": status,
            }))

    @registry.tool("worklogs_submit", write=True, tags=("worklogs",))
    async def worklogs_submit(worklog_id: int, version: int) -> dict[str, Any]:
        """把草稿工时提交审核。"""
        return await session.request("POST", f"/worklogs/{worklog_id}/submit",
                                     json_body={"version": version})

    @registry.tool("worklogs_confirm", write=True, tags=("worklogs",))
    async def worklogs_confirm(worklog_id: int, version: int) -> dict[str, Any]:
        """确认一条工时（需要 WORKLOG_CONFIRM 权限）。"""
        return await session.request("POST", f"/worklogs/{worklog_id}/confirm",
                                     json_body={"version": version})

    @registry.tool("worklogs_reject", write=True, tags=("worklogs",))
    async def worklogs_reject(worklog_id: int, reason: str, version: int) -> dict[str, Any]:
        """驳回一条工时，reason 会让填报人看到。"""
        return await session.request("POST", f"/worklogs/{worklog_id}/reject",
                                     json_body={"reason": reason, "version": version})

    @registry.tool("worklogs_delete", write=True, tags=("worklogs",))
    async def worklogs_delete(worklog_id: int) -> dict[str, Any]:
        """删除一条工时。"""
        await session.request("DELETE", f"/worklogs/{worklog_id}")
        return {"status": "DELETED", "worklogId": worklog_id}
