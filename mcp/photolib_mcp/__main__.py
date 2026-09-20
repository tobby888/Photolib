"""命令行入口。

默认以 stdio 起 MCP 服务（宿主就是这么拉起它的）。另外两个子命令是给人用的：
`login` 在装好之后先把浏览器登录走一遍，`doctor` 查配置和连通性——出问题时，
在终端里看一行明确的报错，比在宿主的日志里翻要快得多。
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from .advice import configuration_notes, render_notes
from .config import ALL_TOOLSETS, Settings, load_settings
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

    # 先把配置读一遍：分组名写错时该看到一行人话，而不是一屏 traceback——
    # 宿主里这条错误只会以"photolib 起不来"的形式出现，人得能一眼看懂。
    try:
        load_settings()
    except ValueError as exc:
        print(f"配置有误：{exc}", file=sys.stderr)
        raise SystemExit(2) from None

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


def _print_notes(settings: Settings, *, has_credentials: bool) -> None:
    """把当前这套配置的后果打在结论旁边。

    收窄的取舍写在 mcp/README.md 里，但出问题的人先跑的是 doctor——提示得在这儿给，
    不能指望他先去翻文档。提示只说"会怎样、想换该改哪个变量"，改不改是他的事。
    """
    print()
    print("提示（都只是建议，改不改由你）：")
    for line in render_notes(configuration_notes(settings, has_credentials=has_credentials)):
        print(line)


async def _doctor() -> int:
    settings = load_settings()
    session = PhotoLibSession(settings)
    server = build_server(settings)
    tools = await server.list_tools()
    skipped = len(getattr(server, "photolib_registry").skipped)
    print(f"站点地址      : {settings.base_url}")
    print(f"接口前缀      : {settings.api_base}")
    print(f"凭据文件      : {settings.credentials_path}")
    print(f"启用分组      : {', '.join(settings.toolsets)}"
          f"（{len(settings.toolsets)}/{len(ALL_TOOLSETS)} 组）")
    print(f"只读模式      : {'是' if settings.read_only else '否'}")
    print(f"已注册工具    : {len(tools)} 个"
          + (f"（另有 {skipped} 个写工具因只读模式未注册）" if skipped else ""))
    try:
        health = await session.raw_request("GET", "/actuator/health", authenticated=False)
        print(f"后端连通性    : HTTP {health.status_code}")
    except Exception as exc:  # noqa: BLE001 - doctor 就是用来把各种失败原样报出来的
        print(f"后端连通性    : 连不上（{exc}）")
        _print_notes(settings, has_credentials=session.credentials() is not None)
        await session.aclose()
        return 1
    has_credentials = session.credentials() is not None
    if not has_credentials:
        print("登录状态      : 未登录（运行 `photolib-mcp login`）")
        _print_notes(settings, has_credentials=False)
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
        _print_notes(settings, has_credentials=True)
        await session.aclose()


if __name__ == "__main__":
    main()
