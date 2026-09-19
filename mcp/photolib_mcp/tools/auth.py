"""登录与身份。整套浏览器登录的原理见 :mod:`photolib_mcp.device_login`。"""

from __future__ import annotations

from typing import Any

from ..device_login import await_approval, start_pairing
from ..errors import PhotoLibError
from ..toolkit import ToolRegistry

#: 单次 `photolib_login` 最多在等待批准上花的时间。配对本身有 10 分钟有效期，
#: 但一个工具调用不该占住宿主那么久——超时就把链接和配对码交回去，让人批准完
#: 再调 `photolib_login_status`，配对不会因为这次超时而失效。
LOGIN_WAIT_SECONDS = 100.0


def register(registry: ToolRegistry) -> None:
    session = registry.session
    #: 上一次发起但还没等到批准的配对。超时之后用它继续等，不必重发。
    pending: dict[str, Any] = {}

    @registry.tool("login", write=True, tags=("auth",))
    async def login() -> dict[str, Any]:
        """在浏览器里登录 PhotoLib，把本机授权给这个 MCP 客户端。

        会打开（或给出）一条站内链接和一串配对码。请把**配对码原样展示给用户**，
        并告诉他：在浏览器里打开那条链接、确认客户端信息无误后手动输入配对码。
        配对码必须由用户本人输入——这是防止别人拿走他身份的那道关卡，不要替他绕过，
        也不要把链接转发给第三方。

        登录成功后凭据保存在本机，后续工具调用无需再次登录；令牌到期会自动续期。
        """
        if session.credentials() is not None:
            who = await _safe_me(registry)
            if who is not None:
                return {
                    "status": "ALREADY_LOGGED_IN",
                    "user": who,
                    "hint": "本机已有可用的登录凭据。要换个账号请先调用 photolib_logout。",
                }

        pairing = await start_pairing(session)
        pending["pairing"] = pairing
        try:
            result = await await_approval(session, pairing, timeout=LOGIN_WAIT_SECONDS)
        except PhotoLibError as exc:
            if exc.code != "PAIRING_TIMEOUT":
                raise
            return {
                "status": "WAITING_FOR_APPROVAL",
                "verificationUrl": pairing.verification_url,
                "userCode": pairing.user_code,
                "browserOpened": pairing.browser_opened,
                "expiresInSeconds": pairing.expires_in,
                "hint": "请让用户在浏览器里打开上面的链接并输入配对码完成批准，"
                        "然后调用 photolib_login_status 查看结果。",
            }
        pending.pop("pairing", None)
        return {
            "status": "LOGGED_IN",
            "user": {
                "username": result.username,
                "displayName": result.display_name,
                "id": result.user_id,
                "permissionGroup": result.permission_group,
            },
        }

    @registry.tool("login_status", write=True, tags=("auth",))
    async def login_status() -> dict[str, Any]:
        """查看登录状态；如果上一次 photolib_login 还在等批准，就继续等一会儿。

        用户说"我批准好了"之后调这个。它不会重新发起配对，所以之前给出的那串
        配对码仍然有效。
        """
        if session.credentials() is not None:
            who = await _safe_me(registry)
            if who is not None:
                return {"status": "LOGGED_IN", "user": who}

        pairing = pending.get("pairing")
        if pairing is None:
            return {
                "status": "NOT_LOGGED_IN",
                "hint": "本机没有登录凭据，也没有待批准的配对。请调用 photolib_login。",
            }
        try:
            result = await await_approval(session, pairing, timeout=LOGIN_WAIT_SECONDS)
        except PhotoLibError as exc:
            if exc.code != "PAIRING_TIMEOUT":
                pending.pop("pairing", None)
                raise
            return {
                "status": "WAITING_FOR_APPROVAL",
                "verificationUrl": pairing.verification_url,
                "userCode": pairing.user_code,
                "hint": "仍未批准。链接和配对码没有变，批准后再调一次本工具即可。",
            }
        pending.pop("pairing", None)
        return {
            "status": "LOGGED_IN",
            "user": {
                "username": result.username,
                "displayName": result.display_name,
                "id": result.user_id,
                "permissionGroup": result.permission_group,
            },
        }

    @registry.tool("whoami", tags=("auth",))
    async def whoami() -> dict[str, Any]:
        """当前登录的是谁，以及他有哪些权限。

        返回里的 `permissions` 决定了这个账号能调用哪些工具。做不了的事就是做不了：
        权限由管理员在权限组里分配，后端才是授权边界。
        """
        return await session.request("GET", "/auth/me")

    @registry.tool("logout", write=True, tags=("auth",))
    async def logout() -> dict[str, Any]:
        """注销本机的登录凭据，并让对应的会话在服务端失效。"""
        credentials = session.credentials()
        if credentials is None:
            return {"status": "NOT_LOGGED_IN"}
        try:
            await session.request(
                "POST", "/auth/mcp/token/revoke",
                json_body={"refreshToken": credentials.refresh_token},
                authenticated=False,
            )
        except PhotoLibError:
            # 服务端注销失败（网络不通、令牌早就失效）不该拦着本机清凭据——
            # 留着一份用不了的令牌只会让下一次调用报一个更含糊的错。
            pass
        session.forget()
        pending.pop("pairing", None)
        return {"status": "LOGGED_OUT"}

    @registry.tool("change_password", write=True, tags=("auth",))
    async def change_password(old_password: str, new_password: str) -> dict[str, Any]:
        """修改当前账号的密码（至少 10 位，且必须同时包含字母和数字）。

        改密会让**所有**会话失效，包括本机这一条，所以改完需要重新 photolib_login。
        """
        await session.request(
            "PUT", "/auth/password",
            json_body={"oldPassword": old_password, "newPassword": new_password},
        )
        session.forget()
        return {
            "status": "PASSWORD_CHANGED",
            "hint": "密码已修改，所有登录会话（含本机）都已失效，请重新调用 photolib_login。",
        }

    @registry.tool("metadata_options", tags=("auth",))
    async def metadata_options() -> dict[str, Any]:
        """系统的枚举与限额：各类状态值、允许的图片类型、单图与批量上限等。

        不确定某个状态字段能填什么时先调它，别猜。
        """
        return await session.request("GET", "/metadata/options")


async def _safe_me(registry: ToolRegistry) -> dict[str, Any] | None:
    """能不能用现有凭据拿到身份。拿不到就当作没登录，让调用方走登录流程。"""
    try:
        return await registry.session.request("GET", "/auth/me")
    except PhotoLibError:
        return None
