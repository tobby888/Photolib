"""统计与导出任务。"""

from __future__ import annotations

import asyncio
from typing import Any

from ..errors import PhotoLibError
from ..toolkit import ToolRegistry, compact
from ..transfers import download_to_path

#: 等导出任务的轮询间隔与最长等待。导出是后台任务，大数据量要跑一会儿。
POLL_INTERVAL_SECONDS = 2.0
MAX_WAIT_SECONDS = 180.0


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("statistics_overview", tags=("statistics",))
    async def statistics_overview(date_from: str | None = None, date_to: str | None = None,
                                  project_id: int | None = None) -> dict[str, Any]:
        """总览计数：选题、需求、图片、采用等。日期是 `YYYY-MM-DD`，两端都包含。"""
        return await session.request("GET", "/statistics/overview", params={
            "from": date_from, "to": date_to, "projectId": project_id,
        })

    @registry.tool("statistics_members", tags=("statistics",))
    async def statistics_members(date_from: str | None = None, date_to: str | None = None,
                                 project_id: int | None = None, campus_id: int | None = None,
                                 user_id: int | None = None) -> list[dict[str, Any]]:
        """按成员汇总的拍摄/修图工时与采用数。"""
        return await session.request("GET", "/statistics/members", params={
            "from": date_from, "to": date_to, "projectId": project_id,
            "campusId": campus_id, "userId": user_id,
        })

    @registry.tool("statistics_member_worklogs", tags=("statistics",))
    async def statistics_member_worklogs(student_id: str, date_from: str | None = None,
                                         date_to: str | None = None, project_id: int | None = None,
                                         campus_id: int | None = None) -> list[dict[str, Any]]:
        """某位成员的工时明细：每条工时来自哪个需求、哪个选题、哪个校区。"""
        return await session.request("GET", "/statistics/members/worklogs", params={
            "studentId": student_id, "from": date_from, "to": date_to,
            "projectId": project_id, "campusId": campus_id,
        })

    @registry.tool("statistics_export", write=True, tags=("statistics",))
    async def statistics_export(export_format: str = "xlsx", date_from: str | None = None,
                                date_to: str | None = None, project_id: int | None = None,
                                campus_id: int | None = None,
                                save_to: str | None = None) -> dict[str, Any]:
        """导出成员统计表。

        `save_to` 给了就等任务跑完并把文件存到本地，否则只返回任务信息，
        之后用 photolib_export_job_get 自己查。
        """
        job = await session.request("POST", "/statistics/members/export", json_body=compact({
            "from": date_from, "to": date_to, "projectId": project_id,
            "campusId": campus_id, "format": export_format,
        }))
        return await _maybe_wait(session, job, save_to, f"statistics.{export_format}")

    @registry.tool("worklogs_export", write=True, tags=("statistics", "worklogs"))
    async def worklogs_export(date_from: str, date_to: str, export_format: str = "xlsx",
                              save_to: str | None = None) -> dict[str, Any]:
        """导出工时明细表。起止日期必填，`YYYY-MM-DD`，两端都包含。"""
        job = await session.request("POST", "/worklogs/export", json_body={
            "from": date_from, "to": date_to, "format": export_format,
        })
        return await _maybe_wait(session, job, save_to, f"worklogs.{export_format}")

    @registry.tool("export_job_get", tags=("statistics",))
    async def export_job_get(job_id: str) -> dict[str, Any]:
        """查一个导出/打包任务的状态；完成后返回里带下载地址。"""
        return await session.request("GET", f"/export-jobs/{job_id}")

    @registry.tool("export_job_download", write=True, tags=("statistics",))
    async def export_job_download(job_id: str, target_path: str,
                                  wait: bool = True) -> dict[str, Any]:
        """把一个已完成（或等它完成）的导出/打包任务下载到本地。"""
        view = await session.request("GET", f"/export-jobs/{job_id}")
        if wait:
            view = await _wait_for_job(session, job_id, view)
        url = view.get("downloadUrl")
        if not url:
            raise PhotoLibError(
                f"任务还没有可下载的文件（当前状态 {_status_of(view)}）。"
                "可以稍后再试，或用 photolib_export_job_get 看失败原因。",
                code="JOB_NOT_READY",
            )
        return await download_to_path(session, url, target_path,
                                      default_name=_file_name_of(view) or f"export-{job_id}")


async def _maybe_wait(session: Any, job: dict[str, Any], save_to: str | None,
                      default_name: str) -> dict[str, Any]:
    job_id = job.get("id") or job.get("jobId")
    if not save_to:
        return {"job": job, "jobId": job_id,
                "hint": "导出在后台进行，用 photolib_export_job_get 查询进度。"}
    view = await _wait_for_job(session, job_id, None)
    saved = await download_to_path(session, view["downloadUrl"], save_to, default_name=default_name)
    return {"jobId": job_id, "saved": saved, "job": view.get("job")}


async def _wait_for_job(session: Any, job_id: str, view: dict[str, Any] | None) -> dict[str, Any]:
    waited = 0.0
    current = view
    while True:
        if current is None:
            current = await session.request("GET", f"/export-jobs/{job_id}")
        status = _status_of(current)
        if current.get("downloadUrl"):
            return current
        if status in {"FAILED", "CANCELLED"}:
            raise PhotoLibError(
                f"导出任务失败（{status}）：{_failure_of(current) or '后端未给出原因'}",
                code="JOB_FAILED",
            )
        if waited >= MAX_WAIT_SECONDS:
            raise PhotoLibError(
                f"等待导出任务超时（已等 {int(waited)} 秒，当前状态 {status}）。"
                f"任务仍在后台进行，稍后用 photolib_export_job_get 查 {job_id} 即可。",
                code="JOB_TIMEOUT",
            )
        await asyncio.sleep(POLL_INTERVAL_SECONDS)
        waited += POLL_INTERVAL_SECONDS
        current = None


def _status_of(view: dict[str, Any]) -> str:
    job = view.get("job") or {}
    return job.get("status") or view.get("status") or "UNKNOWN"


def _failure_of(view: dict[str, Any]) -> str:
    job = view.get("job") or {}
    return job.get("failureReason") or job.get("errorMessage") or ""


def _file_name_of(view: dict[str, Any]) -> str:
    job = view.get("job") or {}
    return job.get("fileName") or ""
