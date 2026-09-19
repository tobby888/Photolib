"""站内消息：收件箱、已读状态与发送。"""

from __future__ import annotations

from typing import Any

from ..errors import PhotoLibError
from ..toolkit import ToolRegistry, compact


def register(registry: ToolRegistry) -> None:
    session = registry.session

    @registry.tool("notifications_list", tags=("notifications",))
    async def notifications_list(unread_only: bool = False) -> list[dict[str, Any]]:
        """当前账号的站内消息。"""
        return await session.request("GET", "/notifications", params={"unreadOnly": unread_only})

    @registry.tool("notifications_get", tags=("notifications",))
    async def notifications_get(notification_id: int) -> dict[str, Any]:
        """一条站内消息的详情。"""
        return await session.request("GET", f"/notifications/{notification_id}")

    @registry.tool("notifications_unread_count", tags=("notifications",))
    async def notifications_unread_count() -> dict[str, Any]:
        """未读消息数。"""
        return await session.request("GET", "/notifications/unread-count")

    @registry.tool("notifications_mark_read", write=True, tags=("notifications",))
    async def notifications_mark_read(notification_id: int | None = None,
                                      all_messages: bool = False) -> dict[str, Any]:
        """把某条消息标记为已读；`all_messages` 为 true 则全部标记已读。"""
        if all_messages:
            await session.request("POST", "/notifications/read-all")
            return {"status": "ALL_READ"}
        if notification_id is None:
            raise PhotoLibError("要么给 notification_id，要么把 all_messages 置为 true",
                                code="VALIDATION_ERROR")
        await session.request("POST", f"/notifications/{notification_id}/read")
        return {"status": "READ", "notificationId": notification_id}

    @registry.tool("notifications_send", write=True, tags=("notifications",))
    async def notifications_send(title: str, content_html: str, target_user_id: int | None = None,
                                 broadcast: bool = False) -> dict[str, Any]:
        """给某位成员发站内信，或向全体广播（需要 MESSAGE_SEND 权限）。

        **这是会发给真人的消息**：广播尤其如此。发送前请把标题、正文和收件范围
        复述给用户并取得明确确认，不要自行决定内容或范围。正文是 HTML 片段。
        """
        if not broadcast and target_user_id is None:
            raise PhotoLibError("单独发送时必须指定 target_user_id；要群发请把 broadcast 置为 true",
                                code="VALIDATION_ERROR")
        return await session.request("POST", "/notifications/messages", json_body=compact({
            "broadcast": broadcast, "targetUserId": target_user_id,
            "title": title, "contentHtml": content_html,
        }))

    @registry.tool("notifications_message_recipients", tags=("notifications",))
    async def notifications_message_recipients() -> list[dict[str, Any]]:
        """可以作为站内信收件人的成员列表。"""
        return await session.request("GET", "/users/message-recipients")

    @registry.tool("notification_logs_list", tags=("notifications", "admin"))
    async def notification_logs_list(status: str | None = None,
                                     user_id: int | None = None) -> list[dict[str, Any]]:
        """外发通知（企业微信/邮件）的投递记录，仅管理员。"""
        return await session.request("GET", "/notification-logs", params={
            "status": status, "userId": user_id,
        })

    @registry.tool("notification_logs_retry", write=True, tags=("notifications", "admin"))
    async def notification_logs_retry(log_id: int) -> dict[str, Any]:
        """重投一条失败的外发通知，仅管理员。"""
        await session.request("POST", f"/notification-logs/{log_id}/retry")
        return {"status": "RETRIED", "logId": log_id}
