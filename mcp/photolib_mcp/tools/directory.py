"""通讯录与校区。

拍摄者和工时成员都只能从通讯录里选：上传图片要 `photographer_contact_id`，
填工时要 `member_contact_id`，两者都是这里的成员 id，不能凭姓名硬填。
"""

from __future__ import annotations

from typing import Any

from ..toolkit import ToolRegistry


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("directory_members_list", tags=("directory",))
    async def directory_members_list(campus_id: int | None = None,
                                     enabled: bool | None = None) -> list[dict[str, Any]]:
        """通讯录成员。上传图片或填工时之前先用它拿到成员 id。"""
        return await session.request("GET", "/campus-members", params={
            "campusId": campus_id, "enabled": enabled,
        })

    @registry.tool("directory_members_deduped", tags=("directory",))
    async def directory_members_deduped() -> list[dict[str, Any]]:
        """按学号去重后的成员名单（同一个人挂在多个校区时只出现一次）。"""
        return await session.request("GET", "/campus-members/deduped")

    @registry.tool("directory_members_create", write=True, tags=("directory",))
    async def directory_members_create(student_id: str, name: str,
                                       campus_id: int | None = None) -> dict[str, Any]:
        """往通讯录里加一位成员（需要 DIRECTORY_MANAGE 权限）。

        `campus_id` 省略时落在当前账号所在的校区。
        """
        return await session.request("POST", "/campus-members", json_body={
            "campusId": campus_id, "studentId": student_id, "name": name,
        })

    @registry.tool("directory_members_update", write=True, tags=("directory",))
    async def directory_members_update(member_id: int, student_id: str, name: str,
                                       enabled: bool, version: int) -> dict[str, Any]:
        """修改通讯录成员。"""
        return await session.request("PUT", f"/campus-members/{member_id}", json_body={
            "studentId": student_id, "name": name, "enabled": enabled, "version": version,
        })

    @registry.tool("directory_members_delete", write=True, tags=("directory",))
    async def directory_members_delete(member_id: int) -> dict[str, Any]:
        """从通讯录里删除一位成员。已经被引用的成员通常应当停用而不是删除。"""
        await session.request("DELETE", f"/campus-members/{member_id}")
        return {"status": "DELETED", "memberId": member_id}

    @registry.tool("campuses_list", tags=("directory",))
    async def campuses_list(enabled: bool | None = None) -> list[dict[str, Any]]:
        """校区列表。"""
        return await session.request("GET", "/campuses", params={"enabled": enabled})

    @registry.tool("campuses_get", tags=("directory",))
    async def campuses_get(campus_id: int) -> dict[str, Any]:
        """校区详情。"""
        return await session.request("GET", f"/campuses/{campus_id}")

    @registry.tool("campuses_create", write=True, tags=("directory", "admin"))
    async def campuses_create(code: str, name: str) -> dict[str, Any]:
        """新建校区（仅管理员）。`code` 是 2~32 位的字母数字下划线短横。"""
        return await session.request("POST", "/campuses", json_body={"code": code, "name": name})

    @registry.tool("campuses_update", write=True, tags=("directory", "admin"))
    async def campuses_update(campus_id: int, name: str, enabled: bool,
                              version: int) -> dict[str, Any]:
        """改校区名称或启用状态（仅管理员）。"""
        return await session.request("PUT", f"/campuses/{campus_id}", json_body={
            "name": name, "enabled": enabled, "version": version,
        })

    @registry.tool("campuses_set_enabled", write=True, tags=("directory", "admin"))
    async def campuses_set_enabled(campus_id: int, enabled: bool) -> dict[str, Any]:
        """启用或停用一个校区（仅管理员）。"""
        action = "enable" if enabled else "disable"
        return await session.request("POST", f"/campuses/{campus_id}/{action}")

    @registry.tool("managers_campus_assignable", tags=("directory",))
    async def managers_campus_assignable() -> list[dict[str, Any]]:
        """可以被指定负责校区的账号（需要 MANAGER_CAMPUS_ASSIGN 权限）。"""
        return await session.request("GET", "/users/campus-assignable")

    @registry.tool("managers_set_campus", write=True, tags=("directory",))
    async def managers_set_campus(user_id: int, campus_id: int, version: int) -> dict[str, Any]:
        """给校区负责人指定负责校区（需要 MANAGER_CAMPUS_ASSIGN 权限）。"""
        return await session.request("PUT", f"/users/{user_id}/campus", json_body={
            "campusId": campus_id, "version": version,
        })
