"""成员招募：任务管理、报名查看与导出，以及对外的报名通道。"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params
from ..transfers import inspect_batch, put_to_presigned_url, save_bytes

TaskStatus = Literal["DRAFT", "PUBLISHED", "CLOSED"]
DRAFT_TOKEN_HEADER = "X-Recruitment-Draft-Token"


def register(registry: ToolRegistry) -> None:
    session = registry.session

    # ---- 管理端 ---------------------------------------------------------

    @registry.tool("recruitment_tasks_list", tags=("recruitment",))
    async def recruitment_tasks_list(page: int = 1, page_size: int = 20,
                                     keyword: str | None = None,
                                     status: TaskStatus | None = None) -> dict[str, Any]:
        """分页查询招募任务（需要 RECRUITMENT_VIEW 权限）。"""
        return await session.request("GET", "/recruitment-tasks", params=page_params(
            page, page_size, keyword=keyword, status=status))

    @registry.tool("recruitment_tasks_get", tags=("recruitment",))
    async def recruitment_tasks_get(task_id: int) -> dict[str, Any]:
        """招募任务详情，含报名表单结构。"""
        return await session.request("GET", f"/recruitment-tasks/{task_id}")

    @registry.tool("recruitment_tasks_create", write=True, tags=("recruitment",))
    async def recruitment_tasks_create(title: str, form_schema: dict[str, Any],
                                       student_id_label: str, upload_label: str,
                                       starts_at: str, ends_at: str,
                                       intro_markdown: str | None = None,
                                       student_id_help: str | None = None,
                                       upload_help: str | None = None,
                                       upload_required: bool = False) -> dict[str, Any]:
        """新建招募任务（需要 RECRUITMENT_PUBLISH 权限）。

        `form_schema` 形如 `{"fields": [{"id": "grade", "type": "TEXT", "label": "年级",
        "required": true, "options": []}]}`。先用 photolib_recruitment_tasks_get 看一个
        已有任务的结构，照着写最稳妥。时间是 ISO 本地时间。新建出来是草稿。
        """
        return await session.request("POST", "/recruitment-tasks", json_body=compact({
            "title": title, "introMarkdown": intro_markdown, "formSchema": form_schema,
            "studentIdLabel": student_id_label, "studentIdHelp": student_id_help,
            "uploadLabel": upload_label, "uploadHelp": upload_help,
            "uploadRequired": upload_required, "startsAt": starts_at, "endsAt": ends_at,
        }))

    @registry.tool("recruitment_tasks_update", write=True, tags=("recruitment",))
    async def recruitment_tasks_update(task_id: int, title: str, form_schema: dict[str, Any],
                                       student_id_label: str, upload_label: str,
                                       starts_at: str, ends_at: str, version: int,
                                       intro_markdown: str | None = None,
                                       student_id_help: str | None = None,
                                       upload_help: str | None = None,
                                       upload_required: bool = False) -> dict[str, Any]:
        """修改招募任务（全量覆盖，表单结构也会被整体替换）。"""
        return await session.request("PUT", f"/recruitment-tasks/{task_id}", json_body=compact({
            "title": title, "introMarkdown": intro_markdown, "formSchema": form_schema,
            "studentIdLabel": student_id_label, "studentIdHelp": student_id_help,
            "uploadLabel": upload_label, "uploadHelp": upload_help,
            "uploadRequired": upload_required, "startsAt": starts_at, "endsAt": ends_at,
            "version": version,
        }))

    @registry.tool("recruitment_tasks_publish", write=True, tags=("recruitment",))
    async def recruitment_tasks_publish(task_id: int, version: int) -> dict[str, Any]:
        """发布招募任务，报名页对外可见。"""
        return await session.request("POST", f"/recruitment-tasks/{task_id}/publish",
                                     json_body={"version": version})

    @registry.tool("recruitment_tasks_close", write=True, tags=("recruitment",))
    async def recruitment_tasks_close(task_id: int, version: int) -> dict[str, Any]:
        """关闭招募任务，不再接受报名。"""
        return await session.request("POST", f"/recruitment-tasks/{task_id}/close",
                                     json_body={"version": version})

    @registry.tool("recruitment_applications_list", tags=("recruitment",))
    async def recruitment_applications_list(task_id: int, page: int = 1, page_size: int = 20,
                                            student_id: str | None = None) -> dict[str, Any]:
        """某个招募任务下的报名记录。"""
        return await session.request(
            "GET", f"/recruitment-tasks/{task_id}/applications",
            params=page_params(page, page_size, studentId=student_id))

    @registry.tool("recruitment_applications_get", tags=("recruitment",))
    async def recruitment_applications_get(application_id: str) -> dict[str, Any]:
        """一份报名的详情，含逐题作答和上传的作品。"""
        return await session.request("GET", f"/recruitment-applications/{application_id}")

    @registry.tool("recruitment_applications_export", write=True, tags=("recruitment",))
    async def recruitment_applications_export(task_id: int, target_path: str,
                                              student_id: str | None = None) -> dict[str, Any]:
        """把某个任务的报名记录导出成表格并存到本地。

        报名表里是真实的个人信息（姓名、学号、联系方式）。导出的文件请只交给用户本人，
        不要转述内容、不要发到别处。
        """
        response = await session.raw_request(
            "GET", f"/recruitment-tasks/{task_id}/applications/export",
            params={"studentId": student_id}, timeout=session.settings.transfer_timeout)
        return save_bytes(response.content, target_path, f"recruitment-{task_id}.xlsx")

    # ---- 对外报名通道（不需要登录）---------------------------------------

    @registry.tool("recruitment_public_active", tags=("recruitment", "public"))
    async def recruitment_public_active() -> list[dict[str, Any]]:
        """当前开放报名的招募任务（匿名可见的那一份）。"""
        return await session.request("GET", "/public/recruitments", authenticated=False)

    @registry.tool("recruitment_public_create_draft", write=True, tags=("recruitment", "public"))
    async def recruitment_public_create_draft(public_id: str, student_id: str) -> dict[str, Any]:
        """以报名者身份开一份草稿，拿到草稿 id 和草稿令牌。

        后续上传作品、提交报名都要带上这个令牌。
        """
        return await session.request(
            "POST", f"/public/recruitments/{public_id}/drafts",
            authenticated=False, json_body={"studentId": student_id})

    @registry.tool("recruitment_public_upload", write=True, tags=("recruitment", "public"))
    async def recruitment_public_upload(public_id: str, draft_id: str, draft_token: str,
                                        file_paths: list[str]) -> dict[str, Any]:
        """给一份报名草稿上传作品（JPEG/PNG，一批最多 100 张）。"""
        locals_ = inspect_batch(file_paths)
        ticket = await session.request(
            "POST", f"/public/recruitments/{public_id}/drafts/{draft_id}/batches",
            authenticated=False, headers={DRAFT_TOKEN_HEADER: draft_token},
            json_body={"mode": "FILES", "files": [
                {"fileName": item.name, "contentType": item.content_type,
                 "size": item.size, "sha256": item.sha256} for item in locals_]})
        by_name = {item.name: item for item in locals_}
        for entry in ticket["tickets"]:
            local = by_name[entry["fileName"]]
            await put_to_presigned_url(session, entry["uploadUrl"], local, entry["contentType"])
        batch_id = ticket["batchId"]
        return await session.request(
            "POST", f"/public/recruitments/{public_id}/drafts/{draft_id}/batches/{batch_id}/complete",
            authenticated=False, headers={DRAFT_TOKEN_HEADER: draft_token})

    @registry.tool("recruitment_public_upload_status", tags=("recruitment", "public"))
    async def recruitment_public_upload_status(public_id: str, draft_id: str, draft_token: str,
                                               batch_id: str) -> dict[str, Any]:
        """查报名作品批次的处理状态。"""
        return await session.request(
            "GET", f"/public/recruitments/{public_id}/drafts/{draft_id}/batches/{batch_id}",
            authenticated=False, headers={DRAFT_TOKEN_HEADER: draft_token})

    @registry.tool("recruitment_public_submit", write=True, tags=("recruitment", "public"))
    async def recruitment_public_submit(public_id: str, draft_id: str, draft_token: str,
                                        student_id: str,
                                        answers: dict[str, Any]) -> dict[str, Any]:
        """提交报名。`answers` 的 key 是表单字段 id，取自任务的 formSchema。

        提交的是一份真人的报名信息。内容应当来自用户本人，不要替他编造答案。
        """
        return await session.request(
            "POST", f"/public/recruitments/{public_id}/drafts/{draft_id}/submit",
            authenticated=False, headers={DRAFT_TOKEN_HEADER: draft_token},
            json_body={"studentId": student_id, "answers": answers})
