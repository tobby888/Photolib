"""采用（被引）标记与采用排行。"""

from __future__ import annotations

from typing import Any

from ..toolkit import ToolRegistry, compact, page_params


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("adoptions_list", tags=("adoptions",))
    async def adoptions_list(project_id: int, page: int = 1, page_size: int = 50,
                             photographer_student_id: str | None = None) -> dict[str, Any]:
        """某个选题下的采用记录。"""
        return await session.request("GET", f"/projects/{project_id}/adoptions", params=page_params(
            page, page_size, photographerStudentId=photographer_student_id))

    @registry.tool("adoptions_adopt", write=True, tags=("adoptions",))
    async def adoptions_adopt(project_id: int, photo_ids: list[int],
                              remark: str | None = None) -> list[dict[str, Any]]:
        """把选题相册里的若干张图片标记为已采用（一次最多 200 张）。

        采用记录会计入拍摄者的采用排行，也会让图片不能再被删除。
        """
        return await session.request("POST", f"/projects/{project_id}/adoptions",
                                     json_body=compact({"photoIds": photo_ids, "remark": remark}))

    @registry.tool("adoptions_cancel", write=True, tags=("adoptions",))
    async def adoptions_cancel(project_id: int, adoption_id: int) -> dict[str, Any]:
        """撤销一条采用记录。"""
        await session.request("DELETE", f"/projects/{project_id}/adoptions/{adoption_id}")
        return {"status": "CANCELLED", "adoptionId": adoption_id}

    @registry.tool("adoptions_ranking", tags=("adoptions", "statistics"))
    async def adoptions_ranking(date_from: str | None = None, date_to: str | None = None,
                                project_id: int | None = None,
                                campus_id: int | None = None) -> list[dict[str, Any]]:
        """采用排行（按拍摄者）。

        `date_from` / `date_to` 是 `YYYY-MM-DD`，两端都包含在内。
        """
        return await session.request("GET", "/statistics/adoptions/ranking", params={
            "from": date_from, "to": date_to, "projectId": project_id, "campusId": campus_id,
        })
