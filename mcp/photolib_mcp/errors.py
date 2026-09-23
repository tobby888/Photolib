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


class StepUpRequiredError(PhotoLibError):
    """两步验证对这个账号生效，而这次操作（删除、系统管理）要求 15 分钟内再验证过一次。

    后端回的是 403，但它不是"没权限"：权限够，只差本人再确认一次。按
    :class:`PermissionDeniedError` 报的话，模型只会告诉用户"做不了"。
    """

    def __init__(self, message: str = "") -> None:
        super().__init__(
            _sentence(message or "该操作需要先完成两步验证")
            + "请向用户要他验证器 App 上当前显示的 6 位验证码（必须由用户本人提供，不要猜、不要重复试），"
              "调用 photolib_step_up 提交，成功后重新执行刚才的操作。15 分钟内的同类操作不必再验证。"
              "只绑定了安全密钥的账号没法在这里验证，请用户到浏览器里完成这项操作。",
            code="STEP_UP_REQUIRED",
            status=403,
        )


class MfaEnrollmentRequiredError(PhotoLibError):
    """账号所在权限组强制两步验证，但还没绑定设备：除了绑定什么都做不了。"""

    def __init__(self, message: str = "") -> None:
        super().__init__(
            _sentence(message or "请先绑定两步验证设备")
            + "账号所在的权限组要求两步验证，绑定要扫码或插安全密钥，只能由用户本人在浏览器里"
              "登录 PhotoLib 完成；绑好之后这里的操作即可继续。",
            code="MFA_ENROLLMENT_REQUIRED",
            status=403,
        )


def _sentence(text: str) -> str:
    """后端的报错不带句号，后面还要接一句指引。"""
    return text.rstrip("。") + "。"
