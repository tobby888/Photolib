"""选题分享链接：管理端（建链接）与访客端（凭链接 + 密码进）。

访客端那些工具不需要登录：能力完全由"链接 + 密码"换来的分享会话决定，
和本机是否登录无关。密码由用户给出，不要去猜，也不要把拿到的分享会话
或下载地址转发给任何人。
"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params
from ..transfers import download_to_path, inspect_file, put_to_presigned_url

SESSION_HEADER = "X-Share-Session"
SharePurpose = Literal["BROWSE", "UPLOAD"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    # ---- 管理端（需要 PROJECT_SHARE 权限）-------------------------------

    @registry.tool("shares_list", tags=("shares",))
    async def shares_list(project_id: int) -> list[dict[str, Any]]:
        """某个选题下已建立的分享链接。"""
        return await session.request("GET", f"/projects/{project_id}/share-links")

    @registry.tool("shares_create", write=True, tags=("shares",))
    async def shares_create(project_id: int, purpose: SharePurpose = "BROWSE",
                            name: str | None = None, password: str | None = None,
                            allow_download: bool = False, allow_adoption: bool = False,
                            expires_at: str | None = None) -> dict[str, Any]:
        """建立一条分享链接。

        - `purpose`：BROWSE 是看相册，UPLOAD 是让外部的人往活动选题里传图。
        - `password` 留空则由后端生成一个，返回里会给出明文——**只会给这一次**。
        - `expires_at` 是 ISO 本地时间，留空表示不过期。

        这条链接拿到手就能用（还要密码），等于把选题相册交出去了。建之前请跟用户
        确认用途、是否允许下载/标记被引，以及有效期。
        """
        return await session.request("POST", f"/projects/{project_id}/share-links",
                                     json_body=compact({
                                         "purpose": purpose, "name": name, "password": password,
                                         "allowDownload": allow_download,
                                         "allowAdoption": allow_adoption,
                                         "expiresAt": expires_at,
                                     }))

    @registry.tool("shares_update", write=True, tags=("shares",))
    async def shares_update(project_id: int, link_id: int, version: int,
                            name: str | None = None, allow_download: bool = False,
                            allow_adoption: bool = False,
                            expires_at: str | None = None) -> dict[str, Any]:
        """改一条分享链接的名字、下载/被引开关和有效期。开关改完对已经打开页面的访客立即生效。"""
        return await session.request(
            "PUT", f"/projects/{project_id}/share-links/{link_id}", json_body=compact({
                "name": name, "allowDownload": allow_download,
                "allowAdoption": allow_adoption, "expiresAt": expires_at, "version": version,
            }))

    @registry.tool("shares_reset_password", write=True, tags=("shares",))
    async def shares_reset_password(project_id: int, link_id: int,
                                    password: str | None = None) -> dict[str, Any]:
        """重置分享密码。留空则自动生成。重置会让已经发出去的访客会话立刻失效。"""
        return await session.request(
            "POST", f"/projects/{project_id}/share-links/{link_id}/password",
            json_body=compact({"password": password}))

    @registry.tool("shares_delete", write=True, tags=("shares",))
    async def shares_delete(project_id: int, link_id: int) -> dict[str, Any]:
        """删除一条分享链接，已发出的会话立刻失效。"""
        await session.request("DELETE", f"/projects/{project_id}/share-links/{link_id}")
        return {"status": "DELETED", "linkId": link_id}

    # ---- 访客端（凭 token + 密码，不需要登录）----------------------------

    @registry.tool("shares_guest_greet", tags=("shares", "public"))
    async def shares_guest_greet(token: str) -> dict[str, Any]:
        """看一条分享链接的门面：它是相册还是上传台、要不要密码、有没有过期。"""
        return await session.request("GET", f"/public/shares/{token}", authenticated=False)

    @registry.tool("shares_guest_open", write=True, tags=("shares", "public"))
    async def shares_guest_open(token: str, password: str, uploader_name: str | None = None,
                                uploader_student_id: str | None = None) -> dict[str, Any]:
        """用密码换一个访客会话，返回里的会话串要传给后面的访客工具。

        上传台（UPLOAD 链接）还要报上传者的姓名和学号。
        """
        return await session.request(
            "POST", f"/public/shares/{token}/sessions", authenticated=False,
            json_body=compact({"password": password, "uploaderName": uploader_name,
                               "uploaderStudentId": uploader_student_id}))

    @registry.tool("shares_guest_access", tags=("shares", "public"))
    async def shares_guest_access(token: str, share_session: str) -> dict[str, Any]:
        """这个访客会话当前能做什么（下载、标记被引等开关的实时状态）。"""
        return await session.request("GET", f"/public/shares/{token}/access",
                                     authenticated=False, headers=_guest(share_session))

    @registry.tool("shares_guest_photos", tags=("shares", "public"))
    async def shares_guest_photos(token: str, share_session: str, page: int = 1,
                                  page_size: int = 30, keyword: str | None = None,
                                  tags: list[str] | None = None, taken_from: str | None = None,
                                  taken_to: str | None = None,
                                  photographers: list[str] | None = None,
                                  adoption: str | None = None) -> dict[str, Any]:
        """以访客身份浏览相册。看得到的恰好是这个选题的相册，不多一张。"""
        return await session.request(
            "GET", f"/public/shares/{token}/photos", authenticated=False,
            headers=_guest(share_session),
            params=page_params(page, page_size, keyword=keyword, tags=tags,
                               takenFrom=taken_from, takenTo=taken_to,
                               photographers=photographers, adoption=adoption))

    @registry.tool("shares_guest_filter_options", tags=("shares", "public"))
    async def shares_guest_filter_options(token: str, share_session: str) -> dict[str, Any]:
        """访客相册里可用的筛选项（标签、拍摄者等）。"""
        return await session.request("GET", f"/public/shares/{token}/photo-filter-options",
                                     authenticated=False, headers=_guest(share_session))

    @registry.tool("shares_guest_download", write=True, tags=("shares", "public"))
    async def shares_guest_download(token: str, share_session: str, photo_id: int,
                                    target_path: str | None = None) -> dict[str, Any]:
        """以访客身份取一张图的下载地址；给了 `target_path` 就直接存到本地。"""
        link = await session.request(
            "POST", f"/public/shares/{token}/photos/{photo_id}/download-url",
            authenticated=False, headers=_guest(share_session))
        if not target_path:
            return link
        saved = await download_to_path(session, link["downloadUrl"], target_path,
                                       default_name=link.get("fileName") or f"photo-{photo_id}.jpg")
        return {"saved": saved, "link": link}

    @registry.tool("shares_guest_batch_download", write=True, tags=("shares", "public"))
    async def shares_guest_batch_download(token: str, share_session: str,
                                          photo_ids: list[int]) -> dict[str, Any]:
        """以访客身份发起打包下载（一次最多 200 张）。"""
        return await session.request(
            "POST", f"/public/shares/{token}/batch-downloads", authenticated=False,
            headers=_guest(share_session), json_body={"photoIds": photo_ids})

    @registry.tool("shares_guest_batch_download_status", tags=("shares", "public"))
    async def shares_guest_batch_download_status(token: str, share_session: str,
                                                 job_id: str) -> dict[str, Any]:
        """查访客打包任务的进度。任务只能通过创建它的那条链接查到。"""
        return await session.request(
            "GET", f"/public/shares/{token}/batch-downloads/{job_id}",
            authenticated=False, headers=_guest(share_session))

    @registry.tool("shares_guest_adopt", write=True, tags=("shares", "public"))
    async def shares_guest_adopt(token: str, share_session: str, photo_id: int,
                                 adopted: bool = True) -> dict[str, Any]:
        """以访客身份标记/取消标记"被引用"。站内和其它链接上都会立刻看到。"""
        method = "POST" if adopted else "DELETE"
        return await session.request(
            method, f"/public/shares/{token}/photos/{photo_id}/adoption",
            authenticated=False, headers=_guest(share_session))

    @registry.tool("shares_guest_upload", write=True, tags=("shares", "public"))
    async def shares_guest_upload(token: str, share_session: str, file_path: str,
                                  title: str | None = None, description: str | None = None,
                                  taken_at: str | None = None) -> dict[str, Any]:
        """通过上传链接传一张图（不需要登录）。`taken_at` 留空按当前时间算。"""
        local = inspect_file(file_path)
        ticket = await session.request(
            "POST", f"/public/shares/{token}/upload-tickets", authenticated=False,
            headers=_guest(share_session), json_body=compact({
                "fileName": local.name, "contentType": local.content_type,
                "size": local.size, "sha256": local.sha256, "takenAt": taken_at,
            }))
        await put_to_presigned_url(session, ticket["uploadUrl"], local,
                                   ticket.get("contentType") or local.content_type)
        return await session.request(
            "POST", f"/public/shares/{token}/uploads/{ticket['photoId']}/complete",
            authenticated=False, headers=_guest(share_session),
            json_body=compact({"title": title, "description": description}))

    @registry.tool("shares_guest_upload_status", tags=("shares", "public"))
    async def shares_guest_upload_status(token: str, share_session: str,
                                         photo_id: int) -> dict[str, Any]:
        """查一张访客上传图片的处理状态。"""
        return await session.request(
            "GET", f"/public/shares/{token}/uploads/{photo_id}",
            authenticated=False, headers=_guest(share_session))

    @registry.tool("shares_guest_upload_zip", write=True, tags=("shares", "public"))
    async def shares_guest_upload_zip(token: str, share_session: str, archive_path: str,
                                      taken_at: str | None = None) -> dict[str, Any]:
        """通过上传链接传一个 ZIP 压缩包，服务端解包成多张图片。

        解包是异步的：这里传完并提交，之后用 photolib_shares_guest_upload_batch_status
        看结果，完成后再调 photolib_shares_guest_upload_batch_finish 落成照片。
        """
        local = inspect_file(archive_path, require_image=False)
        ticket = await session.request(
            "POST", f"/public/shares/{token}/upload-batches", authenticated=False,
            headers=_guest(share_session),
            json_body={"archiveFileName": local.name, "archiveSize": local.size})
        entry = ticket["tickets"][0]
        await put_to_presigned_url(session, entry["uploadUrl"], local, entry["contentType"])
        batch_id = ticket["batchId"]
        await session.request(
            "POST", f"/public/shares/{token}/upload-batches/{batch_id}/complete",
            authenticated=False, headers=_guest(share_session))
        return {"batchId": batch_id,
                "hint": "解包在后台进行，用 photolib_shares_guest_upload_batch_status 查询；"
                        "完成后调用 photolib_shares_guest_upload_batch_finish 落成照片。"}

    @registry.tool("shares_guest_upload_batch_status", tags=("shares", "public"))
    async def shares_guest_upload_batch_status(token: str, share_session: str,
                                               batch_id: str) -> dict[str, Any]:
        """查访客 ZIP 批次的解包与处理结果（含失败原因）。"""
        return await session.request(
            "GET", f"/public/shares/{token}/upload-batches/{batch_id}",
            authenticated=False, headers=_guest(share_session))

    @registry.tool("shares_guest_upload_batch_finish", write=True, tags=("shares", "public"))
    async def shares_guest_upload_batch_finish(token: str, share_session: str, batch_id: str,
                                               taken_at: str | None = None) -> dict[str, Any]:
        """把解包完成的一批落成照片。`taken_at` 留空按当前时间算。"""
        return await session.request(
            "POST", f"/public/shares/{token}/upload-batches/{batch_id}/finish",
            authenticated=False, headers=_guest(share_session),
            json_body=compact({"takenAt": taken_at}))


def _guest(share_session: str) -> dict[str, str]:
    return {SESSION_HEADER: share_session}
