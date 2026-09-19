"""对外报错。

MCP 工具的错误信息是直接给模型看的，所以它必须**说清下一步做什么**，而不只是
复述一个状态码。尤其是没登录这一条：模型看到"请先调用 photolib_login"才会去调，
看到"401 Unauthorized"多半只会把原话转述给用户。
"""

from __future__ import annotations


class PhotoLibError(RuntimeError):
    """PhotoLib 侧可预期的失败。"""

    def __init__(self, message: str, *, code: str = "", status: int | None = None,
                 details: list[str] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.status = status
        self.details = details or []

    def __str__(self) -> str:
        parts = [super().__str__()]
        if self.details:
            parts.append("（" + "；".join(self.details) + "）")
        if self.code:
            parts.append(f"[{self.code}]")
        return " ".join(parts)


class NotAuthenticatedError(PhotoLibError):
    """本机还没有可用的令牌，或者令牌已经彻底失效。"""

    def __init__(self, message: str = "") -> None:
        super().__init__(
            message or "尚未登录 PhotoLib。请调用 photolib_login 工具，"
                       "它会给出一条浏览器链接和一串配对码，由本人在浏览器里批准后即可继续。",
            code="NOT_AUTHENTICATED",
            status=401,
        )


class PermissionDeniedError(PhotoLibError):
    """登录了，但这个账号没有做这件事的权限。"""

    def __init__(self, message: str = "") -> None:
        super().__init__(
            message or "当前账号没有执行该操作的权限。权限由管理员在权限组里分配，"
                       "MCP 不会、也无法绕过它。",
            code="FORBIDDEN",
            status=403,
        )
