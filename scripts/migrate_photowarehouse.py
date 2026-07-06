#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PhotoWarehouse -> PhotoLib 迁移工具（两段式：export / import）。

设计目标：旧系统和新系统可能不在同一台机器、甚至不能直连数据库。因此拆成两步：

  1. export  在旧服务器上运行，连接旧 PostgreSQL，把【全部表原样】导出成一个 JSON 包。
             不需要新库、不需要 OSS 凭据。产物是一个可离线传输的 .json 文件。

  2. import  在新服务器上运行，读取 JSON 包：
               - 把旧 OSS Bucket 的对象复制到新 Bucket（旧 Bucket 之后会删除）；
               - 把可映射的业务数据写入新 MySQL（app_user / project / photo）；
               - 把每一行旧数据完整归档到 legacy_archive_record，避免任何字段丢失。
             写库是幂等的：靠 legacy_migration_item 记录新旧主键映射，可安全重跑。

本脚本在 Linux 上运行。用法见 README.md。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path

from sqlalchemy import MetaData, create_engine, inspect, select, text

# JSON 包格式版本；结构变化时递增，import 端据此校验。
PACKAGE_VERSION = 1


# --------------------------------------------------------------------------- #
# 通用工具
# --------------------------------------------------------------------------- #
def normalize_url(raw: str | None, kind: str) -> str:
    """把常见的 DB URL 前缀改写成 SQLAlchemy 驱动形式。"""
    if not raw:
        raise RuntimeError(f"缺少 {kind} 数据库 URL")
    replacements = {
        "postgres://": "postgresql+psycopg2://",
        "postgresql://": "postgresql+psycopg2://",
        "mysql://": "mysql+pymysql://",
        "jdbc:postgresql://": "postgresql+psycopg2://",
        "jdbc:mysql://": "mysql+pymysql://",
    }
    for prefix, replacement in replacements.items():
        if raw.startswith(prefix):
            return replacement + raw[len(prefix):]
    return raw


def encode_value(v):
    """把任意 DB 值编码成 JSON 可序列化形式，并保留类型信息以便无损还原。"""
    if v is None or isinstance(v, (str, int, float, bool)):
        return v
    if isinstance(v, (datetime, date, time)):
        return {"$type": "datetime", "value": v.isoformat()}
    if isinstance(v, Decimal):
        return {"$type": "decimal", "value": str(v)}
    if isinstance(v, (bytes, bytearray, memoryview)):
        return {"$type": "bytes", "value": base64.b64encode(bytes(v)).decode()}
    return {"$type": "str", "value": str(v)}


def decode_value(v):
    """encode_value 的逆过程，返回适合写入 MySQL 的 Python 值。"""
    if not isinstance(v, dict) or "$type" not in v:
        return v
    kind = v["$type"]
    raw = v.get("value")
    if raw is None:
        return None
    if kind == "datetime":
        # 统一交给数据库驱动处理；datetime 用 fromisoformat 还原，date/time 亦可。
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            return raw
    if kind == "decimal":
        return Decimal(raw)
    if kind == "bytes":
        return base64.b64decode(raw)
    return raw


def clipped(v, size: int, fallback: str | None = None) -> str | None:
    """裁剪字符串到列宽；空值用 fallback 兜底。"""
    value = str(v).strip() if v is not None else ""
    value = value or fallback
    return value[:size] if value is not None else None


# --------------------------------------------------------------------------- #
# JSON 包读写
# --------------------------------------------------------------------------- #
class Package:
    """内存中的导出包，包含每张旧表的全部行（原样编码）。"""

    def __init__(self, tables: dict[str, list[dict]], meta: dict):
        self.tables = tables
        self.meta = meta

    def rows(self, table: str) -> list[dict]:
        """返回某张表已解码为 Python 值的行列表；表不存在时返回空列表。"""
        return [
            {k: decode_value(val) for k, val in row.items()}
            for row in self.tables.get(table, [])
        ]

    @classmethod
    def load(cls, path: Path) -> "Package":
        doc = json.loads(path.read_text(encoding="utf-8"))
        if doc.get("package_version") != PACKAGE_VERSION:
            raise RuntimeError(
                f"JSON 包版本不兼容：期望 {PACKAGE_VERSION}，实际 {doc.get('package_version')}"
            )
        return cls(doc["tables"], doc.get("meta", {}))


# --------------------------------------------------------------------------- #
# export：旧库 -> JSON
# --------------------------------------------------------------------------- #
def export_json(source_url: str, out_path: Path, source_schema: str) -> dict:
    """把旧库全部表原样导出为一个 JSON 包，返回每张表的行数统计。"""
    engine = create_engine(normalize_url(source_url, "旧"), pool_pre_ping=True)
    meta = MetaData()
    meta.reflect(bind=engine, schema=source_schema)

    tables: dict[str, list[dict]] = {}
    counts: dict[str, int] = {}
    with engine.connect() as db:
        for table in meta.sorted_tables:
            rows = [
                {str(k): encode_value(v) for k, v in row.items()}
                for row in db.execute(select(table)).mappings()
            ]
            tables[table.name] = rows
            counts[table.name] = len(rows)
            print(f"导出 {table.name}: {len(rows)} 行", flush=True)

    document = {
        "package_version": PACKAGE_VERSION,
        "meta": {
            "exported_at": datetime.now().isoformat(),
            "source_schema": source_schema,
            "row_counts": counts,
        },
        "tables": tables,
    }
    out_path.write_text(
        json.dumps(document, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"已写出 JSON 包: {out_path}（{sum(counts.values())} 行）", flush=True)
    return counts


# --------------------------------------------------------------------------- #
# import：JSON -> 新库 + OSS
# --------------------------------------------------------------------------- #
def mapped(db, kind: str, old_id):
    if old_id is None:
        return None
    return db.execute(
        text("SELECT target_id FROM legacy_migration_item "
             "WHERE source_type=:t AND source_id=:i"),
        {"t": kind, "i": old_id},
    ).scalar_one_or_none()


def remember(db, kind: str, old_id, new_id):
    db.execute(
        text("INSERT INTO legacy_migration_item(source_type,source_id,target_id) "
             "VALUES(:t,:i,:n) ON DUPLICATE KEY UPDATE target_id=VALUES(target_id)"),
        {"t": kind, "i": old_id, "n": new_id},
    )


def archive(db, pkg: Package) -> dict:
    """把 JSON 包中每一行完整写入 legacy_archive_record，可重跑。"""
    sql = text(
        "INSERT INTO legacy_archive_record(source_table,source_key,payload_json) "
        "VALUES(:t,:k,CAST(:p AS JSON)) "
        "ON DUPLICATE KEY UPDATE payload_json=VALUES(payload_json)"
    )
    counts = {}
    for table_name, rows in pkg.tables.items():
        batch, total = [], 0
        for ordinal, row in enumerate(rows, 1):
            # 归档保存的是原始编码后的行，携带类型信息，日后可无损重建。
            pk = row.get("id")
            key_source = json.dumps(
                decode_value(pk) if pk is not None else ordinal,
                ensure_ascii=False, default=str,
            )
            if len(key_source) > 512:
                key_source = "sha256:" + hashlib.sha256(key_source.encode()).hexdigest()
            batch.append({
                "t": table_name,
                "k": key_source,
                "p": json.dumps(row, ensure_ascii=False, separators=(",", ":")),
            })
            if len(batch) == 500:
                db.execute(sql, batch)
                total += len(batch)
                batch = []
        if batch:
            db.execute(sql, batch)
            total += len(batch)
        counts[table_name] = total
    return counts


def remap_key(key: str | None, source_prefix: str, destination_prefix: str) -> str | None:
    """把旧对象 key 改写到新前缀；两个前缀都为空时保持不变。"""
    if not key:
        return key
    sp, dp = source_prefix.strip("/"), destination_prefix.strip("/")
    if sp and not (key == sp or key.startswith(sp + "/")):
        return key  # 不在源前缀下，原样保留
    relative = key[len(sp):].lstrip("/") if sp else key.lstrip("/")
    return f"{dp}/{relative}" if dp else relative


def collect_object_keys(pkg: Package, source_prefix: str, destination_prefix: str) -> dict[str, str]:
    """从 photos 表收集所有需要复制的对象 key（原图 + 预览图），返回 旧key->新key。"""
    mapping: dict[str, str] = {}
    for r in pkg.rows("photos"):
        for old_key in (r.get("file_path"), r.get("preview_path"), r.get("thumbnail_path")):
            if old_key and old_key not in mapping:
                mapping[old_key] = remap_key(old_key, source_prefix, destination_prefix)
    return mapping


def copy_oss(a, key_mapping: dict[str, str]) -> dict:
    """把旧 Bucket 中被引用的对象复制到新 Bucket。可断点重跑。"""
    import oss2

    required = [
        a.old_oss_endpoint, a.old_oss_bucket, a.old_oss_access_key_id, a.old_oss_access_key_secret,
        a.new_oss_endpoint, a.new_oss_bucket, a.new_oss_access_key_id, a.new_oss_access_key_secret,
    ]
    if not all(required):
        raise RuntimeError("OSS 配置不完整；必须提供全部 OLD_OSS_* 和 NEW_OSS_*")

    old = oss2.Bucket(
        oss2.Auth(a.old_oss_access_key_id, a.old_oss_access_key_secret),
        a.old_oss_endpoint, a.old_oss_bucket,
    )
    new = oss2.Bucket(
        oss2.Auth(a.new_oss_access_key_id, a.new_oss_access_key_secret),
        a.new_oss_endpoint, a.new_oss_bucket,
    )

    stats = Counter()
    for old_key, new_key in key_mapping.items():
        try:
            meta = old.head_object(old_key)
        except oss2.exceptions.NoSuchKey:
            stats["missing_source"] += 1
            print(f"[警告] 旧 Bucket 缺少对象: {old_key}", file=sys.stderr, flush=True)
            continue

        # 已存在且大小一致则跳过，支持中断后续跑。
        try:
            existing = new.head_object(new_key)
            if existing.content_length == meta.content_length:
                stats["skipped_same_size"] += 1
                continue
        except oss2.exceptions.NoSuchKey:
            pass

        stream = old.get_object(old_key)
        try:
            headers = {}
            if meta.content_type:
                headers["Content-Type"] = meta.content_type
            new.put_object(new_key, stream, headers=headers)
        finally:
            stream.close()

        verified = new.head_object(new_key)
        if verified.content_length != meta.content_length:
            raise RuntimeError(f"OSS 对象校验失败: {old_key} -> {new_key}")
        stats["copied"] += 1
        stats["bytes"] += meta.content_length
        if stats["copied"] % 100 == 0:
            print(f"OSS 已复制 {stats['copied']} 个对象", flush=True)
    return dict(stats)


def import_core(db, pkg: Package, a) -> dict:
    """把 JSON 包的可映射业务数据写入新库。幂等。"""
    counts = {}

    fallback = a.fallback_user_id or db.execute(text(
        "SELECT id FROM app_user WHERE deleted=FALSE "
        "ORDER BY CASE WHEN role='ADMIN' THEN 0 ELSE 1 END,id LIMIT 1"
    )).scalar_one()

    # ---- users ----
    c = Counter()
    for r in pkg.rows("users"):
        oid = int(r["id"])
        if mapped(db, "USER", oid):
            c["skipped"] += 1
            continue
        username = clipped(r.get("username"), 64, f"legacy-{oid}")
        existing = db.execute(
            text("SELECT id FROM app_user WHERE username=:u"), {"u": username}
        ).scalar_one_or_none()
        if existing:
            remember(db, "USER", oid, existing)
            c["matched"] += 1
            continue
        old_role = str(r.get("role") or "user").lower()
        role = "ADMIN" if old_role == "superadmin" else (
            "MINISTER" if old_role == "admin" else "CAMPUS_MANAGER")
        # 普通 user 默认禁用，等管理员分配校区后再启用，避免越权。
        enabled = not bool(r.get("is_frozen")) and (
            old_role != "user" or a.enable_ordinary_users)
        res = db.execute(text(
            "INSERT INTO app_user "
            "(username,password_hash,display_name,role,email,enabled,must_change_password,"
            " version,deleted,created_at,updated_at) "
            "VALUES(:u,:p,:d,:r,:e,:en,FALSE,1,FALSE,:at,:at)"
        ), {
            "u": username,
            "p": clipped(r.get("hashed_password"), 100, "!invalid!"),
            "d": clipped(r.get("real_name"), 100, username),
            "r": role,
            "e": clipped(r.get("email"), 255),
            "en": enabled,
            "at": r.get("created_date") or datetime.now(),
        })
        remember(db, "USER", oid, res.lastrowid)
        c["inserted"] += 1
    counts["users"] = dict(c)

    # ---- projects ----
    c = Counter()
    for r in pkg.rows("projects"):
        oid = int(r["id"])
        if mapped(db, "PROJECT", oid):
            c["skipped"] += 1
            continue
        res = db.execute(text(
            "INSERT INTO project "
            "(title,description,status,created_by,version,deleted,created_at,updated_at) "
            "VALUES(:t,:d,'ACTIVE',:u,1,FALSE,:at,:at)"
        ), {
            "t": clipped(r.get("title"), 200, f"旧项目 {oid}"),
            "d": r.get("description"),
            "u": mapped(db, "USER", r.get("created_by")) or fallback,
            "at": r.get("created_date") or datetime.now(),
        })
        remember(db, "PROJECT", oid, res.lastrowid)
        c["inserted"] += 1
    counts["projects"] = dict(c)

    # ---- photos ----
    # 旧库照片与项目没有直接外键，只能通过“作为项目样图”反推所属项目。
    tags = defaultdict(list)
    tag_names = {int(t["id"]): t.get("name") for t in pkg.rows("tags")}
    for pt in pkg.rows("photo_tags"):
        name = tag_names.get(int(pt["tag_id"])) if pt.get("tag_id") is not None else None
        if pt.get("photo_id") is not None and name:
            tags[int(pt["photo_id"])].append(name)

    sample_project_of = {}
    for p in pkg.rows("projects"):
        sample = p.get("sample_photo_id")
        if sample is not None:
            sample_project_of.setdefault(int(sample), int(p["id"]))

    users_by_id = {int(u["id"]): u for u in pkg.rows("users")}

    c = Counter()
    for r in pkg.rows("photos"):
        oid = int(r["id"])
        old_key = r.get("file_path")
        if mapped(db, "PHOTO", oid):
            c["skipped"] += 1
            continue
        if not old_key:
            c["missing_key"] += 1
            continue
        new_key = remap_key(old_key, a.source_prefix, a.destination_prefix)
        existing = db.execute(
            text("SELECT id FROM photo WHERE object_key=:k"), {"k": new_key}
        ).scalar_one_or_none()
        if existing:
            remember(db, "PHOTO", oid, existing)
            c["matched"] += 1
            continue

        uploader = users_by_id.get(int(r["uploaded_by"])) if r.get("uploaded_by") is not None else None
        created = r.get("imported_date") or datetime.now()
        res = db.execute(text(
            "INSERT INTO photo "
            "(project_id,title,photographer_student_id,photographer_name,uploaded_by,campus_id,"
            " taken_at,tags_json,width,height,size,content_type,object_key,thumbnail_object_key,"
            " stored_file_name,sha256,status,version,deleted,created_at,updated_at) "
            "VALUES(:pr,:t,:sid,:pn,:u,NULL,:taken,CAST(:tags AS JSON),:w,:h,:size,:ct,:key,"
            " :preview,:fn,:sha,'AVAILABLE',1,FALSE,:at,:at)"
        ), {
            "pr": mapped(db, "PROJECT", sample_project_of.get(oid)),
            "t": clipped(r.get("original_filename"), 200),
            "sid": clipped(uploader.get("student_id") if uploader else None, 64, f"legacy-photo-{oid}"),
            "pn": clipped(r.get("author"), 100,
                          clipped(uploader.get("real_name") if uploader else None, 100, "旧系统用户")),
            "u": mapped(db, "USER", r.get("uploaded_by")) or fallback,
            "taken": r.get("taken_date") or created,
            "tags": json.dumps(tags[oid], ensure_ascii=False),
            "w": r.get("width"),
            "h": r.get("height"),
            "size": r.get("file_size") or 0,
            "ct": clipped(r.get("mime_type"), 100, "application/octet-stream"),
            "key": new_key,
            "preview": remap_key(r.get("preview_path"), a.source_prefix, a.destination_prefix),
            "fn": clipped(r.get("original_filename"), 255),
            # 注意：这是 object key 的稳定摘要，不是图片内容摘要，不能用于内容去重。
            "sha": hashlib.sha256(new_key.encode()).hexdigest(),
            "at": created,
        })
        remember(db, "PHOTO", oid, res.lastrowid)
        c["inserted"] += 1
    counts["photos"] = dict(c)
    return counts


def import_json(a) -> dict:
    pkg = Package.load(Path(a.input))
    report = {
        "started_at": datetime.now().isoformat(),
        "package_meta": pkg.meta,
    }

    # 1) 先复制 OSS 对象；成功后再写库，保证库里引用的 key 一定已在新 Bucket 中。
    if not a.skip_oss:
        key_mapping = collect_object_keys(pkg, a.source_prefix, a.destination_prefix)
        print(f"待复制 OSS 对象: {len(key_mapping)} 个", flush=True)
        report["oss"] = copy_oss(a, key_mapping)

    # 2) 单事务写库：缺表直接失败，避免半迁移。
    target = create_engine(normalize_url(a.target_url, "新"), pool_pre_ping=True)
    with target.begin() as db:
        required = {"app_user", "project", "photo",
                    "legacy_migration_item", "legacy_archive_record"}
        missing = required - set(inspect(db).get_table_names())
        if missing:
            raise RuntimeError("新库缺少表: " + ",".join(sorted(missing)))
        report["archived"] = archive(db, pkg)
        report["migrated"] = import_core(db, pkg, a)

    report["completed_at"] = datetime.now().isoformat()
    return report


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="PhotoWarehouse -> PhotoLib 迁移工具（export 导出 JSON / import 导入新库）",
    )
    sub = p.add_subparsers(dest="command", required=True)

    e = sub.add_parser("export", help="连接旧库，把全部表原样导出为 JSON 包")
    e.add_argument("--source-url", default=os.getenv("OLD_DATABASE_URL"),
                   help="旧 PostgreSQL 连接串，或用环境变量 OLD_DATABASE_URL")
    e.add_argument("--output", "-o", default="photowarehouse-export.json",
                   help="输出 JSON 包路径")
    e.add_argument("--source-schema", default=os.getenv("OLD_DB_SCHEMA", "public"),
                   help="旧库 schema，默认 public")

    i = sub.add_parser("import", help="读取 JSON 包，复制 OSS 对象并写入新库")
    i.add_argument("--input", "-i", default="photowarehouse-export.json",
                   help="export 生成的 JSON 包路径")
    i.add_argument("--target-url", default=os.getenv("NEW_DATABASE_URL"),
                   help="新 MySQL 连接串，或用环境变量 NEW_DATABASE_URL")
    i.add_argument("--report", default="photowarehouse-import-report.json",
                   help="导入结果报告输出路径")
    i.add_argument("--fallback-user-id", type=int,
                   help="旧库找不到对应用户时，项目/照片归属的兜底用户 ID")
    i.add_argument("--enable-ordinary-users", action="store_true",
                   help="直接启用旧库普通 user（默认禁用，等管理员分配校区）")
    i.add_argument("--skip-oss", action="store_true",
                   help="跳过 OSS 复制（仅在对象已单独迁移时使用，不建议）")
    i.add_argument("--source-prefix", default=os.getenv("OLD_OSS_PREFIX", ""),
                   help="旧对象 key 前缀，用于改写到新前缀")
    i.add_argument("--destination-prefix", default=os.getenv("NEW_OSS_PREFIX", ""),
                   help="新对象 key 前缀")
    i.add_argument("--old-oss-endpoint", default=os.getenv("OLD_OSS_ENDPOINT"))
    i.add_argument("--old-oss-bucket", default=os.getenv("OLD_OSS_BUCKET"))
    i.add_argument("--old-oss-access-key-id", default=os.getenv("OLD_OSS_ACCESS_KEY_ID"))
    i.add_argument("--old-oss-access-key-secret", default=os.getenv("OLD_OSS_ACCESS_KEY_SECRET"))
    i.add_argument("--new-oss-endpoint", default=os.getenv("NEW_OSS_ENDPOINT"))
    i.add_argument("--new-oss-bucket", default=os.getenv("NEW_OSS_BUCKET"))
    i.add_argument("--new-oss-access-key-id", default=os.getenv("NEW_OSS_ACCESS_KEY_ID"))
    i.add_argument("--new-oss-access-key-secret", default=os.getenv("NEW_OSS_ACCESS_KEY_SECRET"))
    return p


def main():
    a = build_parser().parse_args()
    if a.command == "export":
        export_json(a.source_url, Path(a.output), a.source_schema)
        return
    if a.command == "import":
        report = import_json(a)
        Path(a.report).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"导入完成: {a.report}", flush=True)
        return


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 - 顶层统一报错并保留堆栈
        print(f"执行失败: {e}", file=sys.stderr)
        raise
