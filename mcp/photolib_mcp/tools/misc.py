"""零散能力：说明配图、消息配图、头像、首次改密、选片改图，以及通用接口出口。"""

from __future__ import annotations

from typing import Any, Literal

from ..errors import PhotoLibError
from ..toolkit import ToolRegistry, compact
from ..transfers import inspect_file, put_to_presigned_url, save_bytes

Method = Literal["GET", "POST", "PUT", "PATCH", "DELETE"]
_WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("description_image_upload", write=True, tags=("misc",))
    async def description_image_upload(file_path: str) -> dict[str, Any]:
        """上传一张说明配图，返回可以写进选题/需求 Markdown 说明里的地址。

        说明里只能引用这样上传的图片，外部图片链接会被拦掉。
        """
        return await session.request("POST", "/description-images", file_path=file_path)

    @registry.tool("message_image_upload", write=True, tags=("misc",))
    async def message_image_upload(file_path: str) -> dict[str, Any]:
        """上传一张站内信配图，返回可以写进消息正文 HTML 里的地址。"""
        return await session.request("POST", "/notifications/images", file_path=file_path)

    @registry.tool("avatar_set", write=True, tags=("misc",))
    async def avatar_set(file_path: str) -> dict[str, Any]:
        """把一张本地图片设为当前账号的头像。"""
        return await session.request("PUT", "/users/me/avatar", file_path=file_path)

    @registry.tool("avatar_clear", write=True, tags=("misc",))
    async def avatar_clear() -> dict[str, Any]:
        """清掉当前账号的头像，恢复默认。"""
        await session.request("DELETE", "/users/me/avatar")
        return {"status": "CLEARED"}

    @registry.tool("avatar_download", write=True, tags=("misc",))
    async def avatar_download(target_path: str, user_id: int | None = None) -> dict[str, Any]:
        """把某个人的头像存到本地。`user_id` 省略则取当前账号自己的。"""
        path = "/users/me/avatar" if user_id is None else f"/users/{user_id}/avatar"
        response = await session.raw_request("GET", path,
                                             timeout=session.settings.transfer_timeout)
        return save_bytes(response.content, target_path, f"avatar-{user_id or 'me'}.png")

    @registry.tool("initial_password_change", write=True, tags=("misc", "auth"))
    async def initial_password_change(initial_password: str, new_password: str) -> dict[str, Any]:
        """首次登录改密：管理员发的初始密码换成自己的密码。

        新密码至少 10 位，且必须同时包含字母和数字。没改初始密码的账号除了改密什么都做不了。
        """
        await session.request("PUT", "/auth/initial-password", json_body={
            "initialPassword": initial_password, "newPassword": new_password,
        })
        session.forget()
        return {"status": "PASSWORD_CHANGED",
                "hint": "密码已设置，所有会话（含本机）已失效，请重新调用 photolib_login。"}

    @registry.tool("selection_apply_edit", write=True, tags=("misc", "projects"))
    async def selection_apply_edit(project_id: int, photo_id: int,
                                   file_path: str) -> dict[str, Any]:
        """在活动选题的选片台里，用一张本地图片替换某张照片的成品图（裁剪/调色后回传）。

        原图不会被动，替换的是成品图。只有该选题的选片人能做。
        """
        local = inspect_file(file_path)
        ticket = await session.request(
            "POST", f"/projects/{project_id}/selection/photos/{photo_id}/edit-tickets",
            json_body={"contentType": local.content_type, "size": local.size})
        await put_to_presigned_url(session, ticket["uploadUrl"], local,
                                   ticket.get("contentType") or local.content_type)
        return await session.request(
            "POST", f"/projects/{project_id}/selection/photos/{photo_id}/apply-edit",
            json_body={"sourceObjectKey": ticket["sourceObjectKey"], "contentType": local.content_type,
                       "size": local.size, "sha256": local.sha256})

    # 读写都能走，所以不能标成写工具：标了的话只读模式下它整个不注册，
    # 下面那道"只读模式拒绝写方法"的判断就永远轮不到执行，连读都用不了。
    @registry.tool("api_request", tags=("misc",))
    async def api_request(method: Method, path: str, query: dict[str, Any] | None = None,
                          body: dict[str, Any] | None = None) -> Any:
        """直接调用一个 PhotoLib REST 接口（`/api/v1` 之下），用于其它工具没有覆盖到的角落。

        **优先用具体的工具**：它们带着参数校验、上传流程和用法说明，这个没有。
        `path` 形如 `/projects/42`，`query` 和 `body` 分别是查询参数和 JSON 请求体。
        权限仍然由后端判定，这个工具不会、也无法绕过它。
        """
        if not path.startswith("/"):
            path = "/" + path
        if registry.read_only and method in _WRITE_METHODS:
            raise PhotoLibError("当前是只读模式，不能调用写接口", code="READ_ONLY")
        return await session.request(method, path, params=query, json_body=compact(body or {}) or None)
