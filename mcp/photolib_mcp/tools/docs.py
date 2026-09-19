"""文档中心：目录树、正文编辑、PDF 与对外阅读。"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact
from ..transfers import save_bytes

NodeType = Literal["FOLDER", "DOCUMENT", "PDF"]
Visibility = Literal["MEMBERS", "PUBLIC"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    # ---- 编辑端（需要 DOC_MANAGE）---------------------------------------

    @registry.tool("docs_tree", tags=("docs",))
    async def docs_tree() -> list[dict[str, Any]]:
        """编辑视角的完整目录树，含草稿和仅成员可见的文档（需要 DOC_MANAGE）。"""
        return await session.request("GET", "/docs/tree")

    @registry.tool("docs_get", tags=("docs",))
    async def docs_get(node_id: int) -> dict[str, Any]:
        """一个文档节点的详情与正文（需要 DOC_MANAGE）。"""
        return await session.request("GET", f"/docs/{node_id}")

    @registry.tool("docs_create", write=True, tags=("docs",))
    async def docs_create(node_type: NodeType, title: str,
                          parent_id: int | None = None) -> dict[str, Any]:
        """新建文件夹或 Markdown 文档。新建出来是草稿，发布前谁都看不到。"""
        return await session.request("POST", "/docs", json_body=compact({
            "parentId": parent_id, "nodeType": node_type, "title": title,
        }))

    @registry.tool("docs_rename", write=True, tags=("docs",))
    async def docs_rename(node_id: int, title: str, version: int) -> dict[str, Any]:
        """重命名一个节点。"""
        return await session.request("PUT", f"/docs/{node_id}/title",
                                     json_body={"title": title, "version": version})

    @registry.tool("docs_save_content", write=True, tags=("docs",))
    async def docs_save_content(node_id: int, content: str, version: int) -> dict[str, Any]:
        """保存 Markdown 正文（全量覆盖）。改之前先 photolib_docs_get 读一遍。"""
        return await session.request("PUT", f"/docs/{node_id}/content",
                                     json_body={"content": content, "version": version})

    @registry.tool("docs_set_published", write=True, tags=("docs",))
    async def docs_set_published(node_id: int, published: bool, version: int) -> dict[str, Any]:
        """发布或撤回一篇文档。没有正文的文档发布不了。"""
        return await session.request("POST", f"/docs/{node_id}/publication",
                                     json_body={"published": published, "version": version})

    @registry.tool("docs_set_visibility", write=True, tags=("docs",))
    async def docs_set_visibility(node_id: int, visibility: Visibility,
                                  version: int) -> dict[str, Any]:
        """改可见范围：MEMBERS 需要登录才能看，PUBLIC 任何人都能看。

        改可见范围不会顺带改发布状态，两件事互不影响。
        """
        return await session.request("POST", f"/docs/{node_id}/visibility",
                                     json_body={"visibility": visibility, "version": version})

    @registry.tool("docs_move", write=True, tags=("docs",))
    async def docs_move(node_id: int, index: int, version: int,
                        parent_id: int | None = None) -> dict[str, Any]:
        """把节点移到某个父节点下的第 `index` 位（从 0 开始）。

        `parent_id` 传 null 表示移到根。文件夹不能被移进自己的子树。
        """
        return await session.request("POST", f"/docs/{node_id}/move", json_body=compact({
            "parentId": parent_id, "index": index, "version": version,
        }))

    @registry.tool("docs_delete", write=True, tags=("docs",))
    async def docs_delete(node_id: int, version: int) -> dict[str, Any]:
        """删除一个节点。删文件夹会连整棵子树一起删掉，先确认里面有什么。"""
        return await session.request("DELETE", f"/docs/{node_id}", params={"version": version})

    # ---- 阅读端（不需要 DOC_MANAGE，未登录也能看公开文档）-----------------

    @registry.tool("docs_public_tree", tags=("docs", "public"))
    async def docs_public_tree() -> list[dict[str, Any]]:
        """读者视角的目录树。登录会话能多看到"仅成员可见"的那部分。"""
        return await session.request("GET", "/public/docs", authenticated=_has_session(registry))

    @registry.tool("docs_public_get", tags=("docs", "public"))
    async def docs_public_get(public_id: str) -> dict[str, Any]:
        """按公开 id 读一篇文档。"""
        return await session.request("GET", f"/public/docs/{public_id}",
                                     authenticated=_has_session(registry))

    @registry.tool("docs_public_download_file", write=True, tags=("docs", "public"))
    async def docs_public_download_file(public_id: str, target_path: str) -> dict[str, Any]:
        """把一篇 PDF 文档存到本地。"""
        response = await session.raw_request(
            "GET", f"/public/docs/{public_id}/file",
            authenticated=_has_session(registry), timeout=session.settings.transfer_timeout)
        return save_bytes(response.content, target_path, f"{public_id}.pdf")


def _has_session(registry: ToolRegistry) -> bool:
    """带着令牌去读：文档接口匿名也能调，但登录之后能多看到仅成员可见的内容。"""
    return registry.session.credentials() is not None
