"""PhotoLib 的 MCP 服务。

把摄影工作台的能力（选题、需求、图库、工时、统计、消息、招募、文档、分享、
系统管理）开放给 AI 客户端，登录走浏览器批准，不需要在任何配置文件里写密码。
"""

from .config import Settings, load_settings
from .server import build_server

__all__ = ["Settings", "build_server", "load_settings", "__version__"]
__version__ = "0.1.0"
