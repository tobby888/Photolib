"""本地文件的上传与下载。

上传是"三步走"：先向后端换一张票据，再把字节直接 PUT 到票据里那条预签名地址，
最后回来告诉后端传完了。中间那一步不经过后端，所以**请求头必须和签名时用的完全
一致**——`Content-Type` 差一个字符（`image/jpg` 之于 `image/jpeg`）签名就对不上，
对象存储会直接拒绝。因此这里一律回填票据里的 `contentType`，而不是本地重新猜一遍。
"""

from __future__ import annotations

import hashlib
import mimetypes
from dataclasses import dataclass
from pathlib import Path

import httpx

from .errors import PhotoLibError
from .session import PhotoLibSession

#: 后端接受的图片类型（见 /metadata/options 的 allowedImageTypes）。
ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png"}
#: 单张图片的上限，和后端 singleImageMaxBytes 一致。
SINGLE_IMAGE_MAX_BYTES = 104_857_600


@dataclass
class LocalFile:
    path: Path
    name: str
    size: int
    sha256: str
    content_type: str


def inspect_batch(file_paths: list[str], *, require_image: bool = True) -> list[LocalFile]:
    """一批文件，附带批量上传独有的那条检查：文件名不能重复。

    批次票据是按文件名回来的（后端不认本地路径），两个同名文件——放在不同目录里的
    `IMG_0001.jpg` 太常见了——会让"票据 → 本地文件"的对应关系塌成一条，结果是同一张图
    传了两次、另一张的位置空着，而且**不会报错**。这条检查要在签票据之前做完：等对象
    传上去再发现，库里已经建了记录。
    """
    files = [inspect_file(path, require_image=require_image) for path in file_paths]
    seen: dict[str, str] = {}
    for local in files:
        if local.name in seen:
            raise PhotoLibError(
                f"同一批里有两个同名文件：{seen[local.name]} 和 {local.path}。"
                "批次是按文件名对应的，请先把其中一个改名，或者分两批上传。",
                code="DUPLICATE_FILE_NAME",
            )
        seen[local.name] = str(local.path)
    return files


def inspect_file(file_path: str, *, require_image: bool = True) -> LocalFile:
    path = Path(file_path).expanduser()
    if not path.is_file():
        raise PhotoLibError(f"找不到文件：{path}", code="FILE_NOT_FOUND")
    size = path.stat().st_size
    if size == 0:
        raise PhotoLibError(f"文件是空的：{path}", code="EMPTY_FILE")

    content_type = _content_type(path)
    if require_image:
        if content_type not in ALLOWED_IMAGE_TYPES:
            raise PhotoLibError(
                f"只接受 JPEG 和 PNG，这个文件看起来是 {content_type}：{path.name}",
                code="UNSUPPORTED_FILE_TYPE",
            )
        if size > SINGLE_IMAGE_MAX_BYTES:
            raise PhotoLibError(
                f"单张图片不能超过 {SINGLE_IMAGE_MAX_BYTES // 1024 // 1024} MB，"
                f"这个文件是 {size / 1024 / 1024:.1f} MB：{path.name}",
                code="FILE_TOO_LARGE",
            )
    return LocalFile(path=path, name=path.name, size=size,
                     sha256=_sha256(path), content_type=content_type)


async def put_to_presigned_url(session: PhotoLibSession, upload_url: str, local: LocalFile,
                               content_type: str) -> None:
    """把文件直传到预签名地址。

    `content_type` 必须原样来自票据。这里不带任何认证头：预签名地址自己就是凭据，
    多带一个 Authorization 反而会被某些对象存储判成签名不匹配。

    分块读着传，一个 1.5 GB 的压缩包不该先整个进内存；但 `Content-Length` 要显式给出——
    不给的话 httpx 会转成 chunked 传输，而预签名 PUT 那一端往往不收 chunked。
    """
    client = await session.client()

    async def chunks():
        # 同步文件句柄不能直接交给 AsyncClient（它会当成同步流而报错），
        # 所以包一层异步生成器自己读。
        with local.path.open("rb") as stream:
            while True:
                chunk = stream.read(1024 * 1024)
                if not chunk:
                    return
                yield chunk

    response = await client.put(
        upload_url, content=chunks(),
        headers={"Content-Type": content_type, "Content-Length": str(local.size)},
        timeout=session.settings.transfer_timeout,
    )
    if response.status_code >= 400:
        raise PhotoLibError(
            f"文件直传失败（HTTP {response.status_code}）：{response.text[:300]}",
            code="UPLOAD_FAILED", status=response.status_code,
        )


async def download_to_path(session: PhotoLibSession, url: str, target: str,
                           *, default_name: str = "download") -> dict[str, object]:
    """把一条（通常是预签名的）下载地址存到本地。

    `target` 是目录就按服务端给的文件名落在里面，是文件路径就用那个名字。
    """
    destination = Path(target).expanduser()
    if destination.is_dir():
        destination = destination / default_name
    destination.parent.mkdir(parents=True, exist_ok=True)

    client = await session.client()
    written = 0
    async with client.stream("GET", url, timeout=session.settings.transfer_timeout) as response:
        if response.status_code >= 400:
            raise PhotoLibError(
                f"下载失败（HTTP {response.status_code}）", code="DOWNLOAD_FAILED",
                status=response.status_code,
            )
        with destination.open("wb") as out:
            async for chunk in response.aiter_bytes():
                out.write(chunk)
                written += len(chunk)
    return {"path": str(destination), "bytes": written}


def save_bytes(content: bytes, target: str, default_name: str) -> dict[str, object]:
    destination = Path(target).expanduser()
    if destination.is_dir():
        destination = destination / default_name
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(content)
    return {"path": str(destination), "bytes": len(content)}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _content_type(path: Path) -> str:
    """按内容判断类型，扩展名只作兜底。

    改个后缀就能把 PNG 说成 JPEG，而后端是按文件魔数校验的——本地按扩展名猜的话，
    错误会推迟到直传之后才爆出来，那时票据已经签过、记录已经建了。
    """
    with path.open("rb") as stream:
        header = stream.read(12)
    if header.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if header.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if header[:4] == b"PK\x03\x04":
        return "application/zip"
    guessed, _ = mimetypes.guess_type(path.name)
    return guessed or "application/octet-stream"
