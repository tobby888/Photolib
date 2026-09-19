"""命令行入口。

默认以 stdio 起 MCP 服务（宿主就是这么拉起它的）。另外两个子命令是给人用的：
`login` 在装好之后先把浏览器登录走一遍，`doctor` 查配置和连通性——出问题时，
在终端里看一行明确的报错，比在宿主的日志里翻要快得多。
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from .config import load_settings
from .device_login import await_approval, start_pairing
from .errors import PhotoLibError
from .server import build_server
from .session import PhotoLibSession


def main() -> None:
    parser = argparse.ArgumentParser(prog="photolib-mcp", description="PhotoLib 的 MCP 服务")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("serve", help="以 stdio 启动 MCP 服务（默认）")
    sub.add_parser("login", help="在浏览器里登录并把凭据保存到本机")
    sub.add_parser("logout", help="清掉本机保存的凭据")
    sub.add_parser("doctor", help="检查配置、连通性和登录状态")
    args = parser.parse_args()

    command = args.command or "serve"
    if command == "serve":
        build_server().run()
    elif command == "login":
        raise SystemExit(asyncio.run(_login()))
    elif command == "logout":
        raise SystemExit(asyncio.run(_logout()))
    else:
        raise SystemExit(asyncio.run(_doctor()))


async def _login() -> int:
    settings = load_settings()
    session = PhotoLibSession(settings)
    try:
        pairing = await start_pairing(session)
        print(f"请在浏览器里打开：{pairing.verification_url}")
        if pairing.browser_opened:
            print("（已尝试自动打开浏览器）")
        print(f"配对码：{pairing.user_code}")
        print("在批准页输入这串配对码并批准，这里会自动继续。")
        result = await await_approval(session, pairing)
        print(f"登录成功：{result.display_name}（{result.username}）")
        print(f"凭据已保存到 {session.store.path}")
        return 0
    except PhotoLibError as exc:
        print(f"登录失败：{exc}", file=sys.stderr)
        return 1
    finally:
        await session.aclose()


async def _logout() -> int:
    settings = load_settings()
    session = PhotoLibSession(settings)
    try:
        credentials = session.credentials()
        if credentials is not None:
            try:
                await session.request("POST", "/auth/mcp/token/revoke", authenticated=False,
                                      json_body={"refreshToken": credentials.refresh_token})
            except PhotoLibError as exc:
                print(f"（服务端注销未成功：{exc}，仍会清掉本机凭据）", file=sys.stderr)
        print("已清除本机凭据。" if session.forget() else "本机本来就没有凭据。")
        return 0
    finally:
        await session.aclose()


async def _doctor() -> int:
    settings = load_settings()
    session = PhotoLibSession(settings)
    server = build_server(settings)
    tools = await server.list_tools()
    print(f"站点地址      : {settings.base_url}")
    print(f"接口前缀      : {settings.api_base}")
    print(f"凭据文件      : {settings.credentials_path}")
    print(f"启用分组      : {', '.join(settings.toolsets)}")
    print(f"只读模式      : {'是' if settings.read_only else '否'}")
    print(f"已注册工具    : {len(tools)} 个")
    try:
        health = await session.raw_request("GET", "/actuator/health", authenticated=False)
        print(f"后端连通性    : HTTP {health.status_code}")
    except Exception as exc:  # noqa: BLE001 - doctor 就是用来把各种失败原样报出来的
        print(f"后端连通性    : 连不上（{exc}）")
        await session.aclose()
        return 1
    if session.credentials() is None:
        print("登录状态      : 未登录（运行 `photolib-mcp login`）")
        await session.aclose()
        return 0
    try:
        me = await session.request("GET", "/auth/me")
        print(f"登录状态      : {me.get('displayName')}（{me.get('username')}），"
              f"权限组 {me.get('permissionGroupName') or me.get('role')}")
        return 0
    except PhotoLibError as exc:
        print(f"登录状态      : 凭据不可用（{exc}）")
        return 1
    finally:
        await session.aclose()


if __name__ == "__main__":
    main()
