"""图库：检索、上传、元数据、收藏、归档与下载。"""

from __future__ import annotations

from typing import Any, Literal

from ..errors import PhotoLibError
from ..toolkit import ToolRegistry, compact, page_params
from ..transfers import download_to_path, inspect_batch, inspect_file, put_to_presigned_url

PhotoStatus = Literal["UPLOADING", "PROCESSING", "AVAILABLE", "ARCHIVED", "DELETED"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("photos_list", tags=("photos",))
    async def photos_list(page: int = 1, page_size: int = 30, keyword: str | None = None,
                          project_id: int | None = None, request_id: int | None = None,
                          photographer_student_id: str | None = None,
                          photographer_name: str | None = None, uploaded_by: int | None = None,
                          campus_id: int | None = None, status: PhotoStatus | None = None,
                          include_all_statuses: bool = False, favorites_only: bool = False,
                          selectable_only: bool = False,
                          tags: list[str] | None = None) -> dict[str, Any]:
        """分页检索图库。

        默认只返回可用（AVAILABLE）的图片；要连归档的一起看就把 `include_all_statuses`
        置为 true。看得到哪些图片取决于账号的图库可见范围（仅本人上传 / 授权校区 / 全站）。
        """
        return await session.request("GET", "/photos", params=page_params(
            page, page_size, keyword=keyword, projectId=project_id, requestId=request_id,
            photographerStudentId=photographer_student_id, photographerName=photographer_name,
            uploadedBy=uploaded_by, campusId=campus_id, status=status,
            includeAllStatuses=include_all_statuses, favoritesOnly=favorites_only,
            selectableOnly=selectable_only, tags=tags))

    @registry.tool("photos_get", tags=("photos",))
    async def photos_get(photo_id: int) -> dict[str, Any]:
        """单张图片的详情。"""
        return await session.request("GET", f"/photos/{photo_id}")

    @registry.tool("photos_upload", write=True, tags=("photos",))
    async def photos_upload(file_path: str, title: str, photographer_contact_id: int,
                            taken_at: str, request_id: int | None = None,
                            project_id: int | None = None, description: str | None = None,
                            tags: list[str] | None = None) -> dict[str, Any]:
        """上传一张本地图片（JPEG 或 PNG），走完"换票据 → 直传 → 完成"三步。

        - `photographer_contact_id` 是通讯录里的成员 id，用
          photolib_directory_members_list 查；拍摄者必须来自通讯录，不能随便填名字。
        - `taken_at` 是拍摄时间，ISO 本地时间（`2026-09-01T14:30:00`），**不能晚于现在**。
        - `request_id` / `project_id` 二选一或都不传：挂到需求下、挂到选题相册里，或先进图库。

        上传完成后后端会异步压缩并生成预览图，图片会短暂处于 PROCESSING 状态。
        """
        local = inspect_file(file_path)
        ticket = await session.request("POST", "/photos/upload-tickets", json_body=compact({
            "requestId": request_id, "projectId": project_id,
            "fileName": local.name, "contentType": local.content_type, "size": local.size,
            "sha256": local.sha256, "photographerContactId": photographer_contact_id,
            "takenAt": taken_at,
        }))
        # 用票据回的 contentType 直传：签名是按它算的，本地再猜一次只会把签名弄错。
        await put_to_presigned_url(session, ticket["uploadUrl"], local, ticket["contentType"])
        return await session.request(
            "POST", f"/photos/{ticket['photoId']}/complete-upload",
            json_body=compact({"title": title, "description": description, "tags": tags}))

    @registry.tool("photos_batch_upload", write=True, tags=("photos",))
    async def photos_batch_upload(file_paths: list[str], photographer_contact_id: int,
                                  taken_at: str, request_id: int | None = None,
                                  project_id: int | None = None,
                                  description: str | None = None,
                                  tags: list[str] | None = None) -> dict[str, Any]:
        """批量上传多张本地图片（一批最多 100 张）。

        每张图的标题默认取文件名，传完后会给整批统一套上同一份拍摄者、拍摄时间和标签；
        要逐张改标题或标签，用 photolib_photos_batch_item_metadata。
        """
        if not file_paths:
            raise PhotoLibError("至少要给一个文件路径", code="VALIDATION_ERROR")
        if len(file_paths) > 100:
            raise PhotoLibError("一批最多 100 张，请分批上传", code="VALIDATION_ERROR")

        locals_ = inspect_batch(file_paths)
        ticket = await session.request("POST", "/photos/batch-upload-tickets", json_body=compact({
            "mode": "FILES", "requestId": request_id, "projectId": project_id,
            "files": [{"fileName": item.name, "contentType": item.content_type,
                       "size": item.size, "sha256": item.sha256} for item in locals_],
        }))
        by_name = {item.name: item for item in locals_}
        for entry in ticket["tickets"]:
            local = by_name[entry["fileName"]]
            await put_to_presigned_url(session, entry["uploadUrl"], local, entry["contentType"])

        batch_id = ticket["batchId"]
        await session.request("POST", f"/photos/batches/{batch_id}/complete-upload")
        await session.request("PUT", f"/photos/batches/{batch_id}/metadata", json_body=compact({
            "description": description, "photographerContactId": photographer_contact_id,
            "takenAt": taken_at, "tags": tags,
        }))
        return await session.request("GET", f"/photos/batches/{batch_id}")

    @registry.tool("photos_upload_zip", write=True, tags=("photos",))
    async def photos_upload_zip(archive_path: str, photographer_contact_id: int, taken_at: str,
                                request_id: int | None = None, project_id: int | None = None,
                                description: str | None = None,
                                tags: list[str] | None = None) -> dict[str, Any]:
        """上传一个 ZIP 压缩包，由服务端解包成多张图片。

        包里只能放图片，展开后的张数和总大小都有上限（见 photolib_metadata_options）。
        解包是异步的，用 photolib_photos_batch_get 看进度和失败原因。
        """
        local = inspect_file(archive_path, require_image=False)
        if local.content_type != "application/zip":
            raise PhotoLibError(f"这不是一个 ZIP 文件：{local.name}", code="UNSUPPORTED_FILE_TYPE")
        ticket = await session.request("POST", "/photos/batch-upload-tickets", json_body=compact({
            "mode": "ZIP", "requestId": request_id, "projectId": project_id,
            "archiveFileName": local.name, "archiveSize": local.size,
        }))
        entry = ticket["tickets"][0]
        await put_to_presigned_url(session, entry["uploadUrl"], local, entry["contentType"])
        batch_id = ticket["batchId"]
        await session.request("POST", f"/photos/batches/{batch_id}/complete-upload")
        await session.request("PUT", f"/photos/batches/{batch_id}/metadata", json_body=compact({
            "description": description, "photographerContactId": photographer_contact_id,
            "takenAt": taken_at, "tags": tags,
        }))
        return await session.request("GET", f"/photos/batches/{batch_id}")

    @registry.tool("photos_batch_get", tags=("photos",))
    async def photos_batch_get(batch_id: str) -> dict[str, Any]:
        """查一个上传批次的状态与逐条明细（含失败原因）。"""
        return await session.request("GET", f"/photos/batches/{batch_id}")

    @registry.tool("photos_batch_item_metadata", write=True, tags=("photos",))
    async def photos_batch_item_metadata(batch_id: str, item_id: int, title: str,
                                         photographer_contact_id: int, taken_at: str,
                                         description: str | None = None,
                                         tags: list[str] | None = None) -> dict[str, Any]:
        """给批次里的某一条单独设置标题、拍摄者、拍摄时间和标签。"""
        return await session.request(
            "PUT", f"/photos/batches/{batch_id}/items/{item_id}/metadata", json_body=compact({
                "title": title, "description": description,
                "photographerContactId": photographer_contact_id,
                "takenAt": taken_at, "tags": tags,
            }))

    @registry.tool("photos_update", write=True, tags=("photos",))
    async def photos_update(photo_id: int, title: str, photographer_contact_id: int,
                            taken_at: str, version: int, description: str | None = None,
                            tags: list[str] | None = None) -> dict[str, Any]:
        """修改一张图片的元数据。这是全量覆盖，没传的字段会被清空，先读一遍再改。"""
        return await session.request("PUT", f"/photos/{photo_id}", json_body=compact({
            "title": title, "description": description,
            "photographerContactId": photographer_contact_id,
            "takenAt": taken_at, "tags": tags, "version": version,
        }))

    @registry.tool("photos_batch_tags", write=True, tags=("photos",))
    async def photos_batch_tags(photo_ids: list[int], add_tags: list[str] | None = None,
                                remove_tags: list[str] | None = None,
                                project_id: int | None = None) -> list[dict[str, Any]]:
        """给多张图片批量增删标签（一次最多 200 张）。

        在某个选题的场景下操作时带上 `project_id`，新增标签会受该选题的预设标签约束。
        """
        return await session.request("POST", "/photos/batch-tags", json_body=compact({
            "photoIds": photo_ids, "addTags": add_tags,
            "removeTags": remove_tags, "projectId": project_id,
        }))

    @registry.tool("photos_set_campus", write=True, tags=("photos",))
    async def photos_set_campus(photo_id: int, campus_id: int | None, version: int) -> dict[str, Any]:
        """改一张图片归属的校区（仅管理员）。传 null 表示不归属任何校区。"""
        return await session.request("PATCH", f"/photos/{photo_id}/campus",
                                     json_body={"campusId": campus_id, "version": version})

    @registry.tool("photos_favorite", write=True, tags=("photos",))
    async def photos_favorite(photo_id: int, favorited: bool = True) -> dict[str, Any]:
        """收藏或取消收藏一张图片。"""
        if favorited:
            await session.request("PUT", f"/photos/{photo_id}/favorite")
        else:
            await session.request("DELETE", f"/photos/{photo_id}/favorite")
        return {"photoId": photo_id, "favorited": favorited}

    @registry.tool("photos_download_url", write=True, tags=("photos",))
    async def photos_download_url(photo_id: int) -> dict[str, Any]:
        """取一张图片的临时下载地址（签名链接，很快过期，不要保存或转发）。"""
        return await session.request("POST", f"/photos/{photo_id}/download-url")

    @registry.tool("photos_download", write=True, tags=("photos",))
    async def photos_download(photo_id: int, target_path: str) -> dict[str, Any]:
        """把一张图片下载到本地。`target_path` 可以是目录，也可以是完整文件名。"""
        link = await session.request("POST", f"/photos/{photo_id}/download-url")
        return await download_to_path(session, link["downloadUrl"], target_path,
                                      default_name=link.get("fileName") or f"photo-{photo_id}.jpg")

    @registry.tool("photos_archive", write=True, tags=("photos",))
    async def photos_archive(photo_id: int, archived: bool = True) -> dict[str, Any]:
        """归档或恢复一张图片。归档只是下架，不删数据。"""
        action = "archive" if archived else "restore"
        return await session.request("POST", f"/photos/{photo_id}/{action}")

    @registry.tool("photos_delete", write=True, tags=("photos",))
    async def photos_delete(photo_id: int) -> dict[str, Any]:
        """删除一张图片（软删除）。已被采用的图片删不掉。"""
        await session.request("DELETE", f"/photos/{photo_id}")
        return {"status": "DELETED", "photoId": photo_id}

    @registry.tool("photos_batch_delete", write=True, tags=("photos",))
    async def photos_batch_delete(photo_ids: list[int]) -> dict[str, Any]:
        """批量删除图片（一次最多 200 张）。

        批量删除影响面大且不可撤销，执行前请把张数和筛选条件复述给用户确认。
        """
        await session.request("POST", "/photos/batch-delete", json_body={"photoIds": photo_ids})
        return {"status": "DELETED", "count": len(photo_ids)}

    @registry.tool("photos_batch_download", write=True, tags=("photos",))
    async def photos_batch_download(photo_ids: list[int], purpose: str | None = None) -> dict[str, Any]:
        """发起一个打包下载任务（一次最多 200 张），返回任务信息。

        任务是异步的：用 photolib_export_job_get 轮询，完成后拿到下载地址。
        """
        return await session.request("POST", "/photos/batch-download", json_body=compact({
            "photoIds": photo_ids, "purpose": purpose,
        }))

    @registry.tool("photos_preview_generation_status", tags=("photos",))
    async def photos_preview_generation_status() -> dict[str, Any]:
        """后台预览图生成/重建的进度。"""
        return await session.request("GET", "/preview-generation/status")
