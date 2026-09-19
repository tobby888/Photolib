"""在浏览器里登录。

MCP 服务跑在成员自己的机器上，没有浏览器会话；而让它拿账号密码是最差的选择——
密码要写进宿主的配置文件，那份文件会被同步、被截图，泄漏的是整个账号而不是一个
可以单独吊销的会话。所以走设备码：

1. 向后端开一条配对请求，拿到配对号（公开）、设备凭据（本机私有）和配对码（打印给人看）；
2. 把 `<站点>/mcp/authorize?request=<配对号>` 交给成员——能开浏览器就直接拉起；
3. 成员用已登录的会话打开批准页，**手敲**终端上这串配对码，批准；
4. 这边一直轮询，批准后换到一对令牌，落到本机凭据文件里。

配对码必须由人手敲，这是整套流程的关键一环：只凭链接就能批准的话，把链接发给别人、
由别人顺手点一下，就等于把一个以他身份说话的令牌交了出去。后端只存配对码的哈希，
批准页自己也不知道正确答案。服务端那一侧的完整说明见
`backend/src/main/java/cn/photolib/mcp/McpAuthorizationService.java`。
"""

from __future__ import annotations

import asyncio
import time
import webbrowser
from dataclasses import dataclass

from .credentials import StoredCredentials
from .errors import PhotoLibError
from .session import PhotoLibSession


@dataclass
class PairingHandle:
    """一次已经发起、等待批准的配对。"""

    request_id: str
    device_code: str
    user_code: str
    verification_url: str
    expires_in: int
    interval: int
    browser_opened: bool


@dataclass
class LoginResult:
    username: str
    display_name: str
    user_id: int | None
    permission_group: str


async def start_pairing(session: PhotoLibSession) -> PairingHandle:
    settings = session.settings
    data = await session.request(
        "POST", "/auth/mcp/authorizations",
        json_body={"clientName": settings.client_name, "deviceLabel": settings.device_label or None},
        authenticated=False,
    )
    verification_url = f"{settings.base_url}{data['verificationPath']}?request={data['requestId']}"

    browser_opened = False
    if settings.open_browser:
        try:
            # 拉起浏览器只是顺手：失败了也照常返回链接，让人自己复制。没有桌面的
            # 机器上 webbrowser 会直接返回 False，不会抛。
            browser_opened = webbrowser.open(verification_url)
        except Exception:  # noqa: BLE001 - 浏览器拉不起来绝不该让登录失败
            browser_opened = False

    return PairingHandle(
        request_id=data["requestId"],
        device_code=data["deviceCode"],
        user_code=data["userCode"],
        verification_url=verification_url,
        expires_in=int(data.get("expiresIn") or 600),
        interval=max(1, int(data.get("interval") or 2)),
        browser_opened=browser_opened,
    )


async def await_approval(session: PhotoLibSession, pairing: PairingHandle,
                         *, timeout: float | None = None) -> LoginResult:
    """轮询到批准为止，把换到的令牌落盘。

    `timeout` 默认取配对本身的有效期：等过了那一刻再问也没有意义。调用方可以
    传一个更短的值——MCP 工具调用有它自己的超时，一个工具把整整十分钟耗在等待上，
    宿主多半已经先一步放弃了。
    """
    deadline = time.monotonic() + (timeout if timeout is not None else pairing.expires_in)
    while True:
        data = await session.request(
            "POST", "/auth/mcp/token",
            json_body={"requestId": pairing.request_id, "deviceCode": pairing.device_code},
            authenticated=False,
        )
        if data.get("status") == "APPROVED":
            user = data.get("user") or {}
            session.remember(StoredCredentials(
                access_token=data["accessToken"],
                refresh_token=data["refreshToken"],
                username=user.get("username") or "",
                display_name=user.get("displayName") or "",
                user_id=user.get("id"),
            ))
            return LoginResult(
                username=user.get("username") or "",
                display_name=user.get("displayName") or "",
                user_id=user.get("id"),
                permission_group=user.get("permissionGroupName") or user.get("role") or "",
            )
        if time.monotonic() >= deadline:
            raise PhotoLibError(
                "等待批准超时。配对请求本身可能还在有效期内——"
                f"在浏览器里打开 {pairing.verification_url} 并输入配对码 {pairing.user_code} 完成批准后，"
                "再调用 photolib_login_status 查看结果；也可以直接重新调用 photolib_login 重发一条。",
                code="PAIRING_TIMEOUT",
            )
        await asyncio.sleep(pairing.interval)
