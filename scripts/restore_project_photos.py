#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""恢复「项目 -> 照片」归属关系（写入多对多表 photo_project）。

背景
----
旧系统 PhotoWarehouse 里，照片并没有 project_id 外键。一个项目要展示哪些照片，
完全由「标签」决定：项目带一组标签(project_tags)，照片带一组标签(photo_tags)，
项目详情页(static/project.html)用 `GET /photos?tag_ids=...` 拉取照片，后端把每个
tag_id 当作 AND 过滤（Photo.tags.any(...)）。也就是说——

    一张照片属于某项目  <=>  该照片的标签集合  ⊇  该项目的标签集合

迁移脚本 migrate_photowarehouse.py 只把「项目样图 sample_photo_id」这一张照片
写了 project_id，其余照片 project_id 全为 NULL。于是新系统里每个项目只剩 1 张图，
与旧系统不符——这就是“数据丢失”的真相（照片本身没丢，丢的是归属关系）。

一张照片在旧系统里可能同时属于多个项目。新库已改造为多对多：新增迁移
V12__photo_project_membership.sql 建了归属表 photo_project(photo_id, project_id)，
项目相册与照片计数都以它为准（photo.project_id 保留为“主/来源项目”）。本脚本用旧库
导出的 JSON 包(photowarehouse-export.json)作事实来源，按上面的标签规则推导出全部归属，
并把每一条 (photo, project) 写入 photo_project——一图多项目完整还原。

两段式，与迁移脚本一致：
  1. plan   离线：只读 JSON 包，推导归属，产出人类可读报告(CSV)+机器可读计划(JSON)。
            不连任何数据库，可反复运行、先给人看。
  2. apply  连新库 MySQL：把旧 photo/project id 翻译成新 id，INSERT 进 photo_project。
            靠主键 (photo_id, project_id) 天然幂等（ON DUPLICATE KEY 跳过），可安全重跑。
            支持 --dry-run（只数不写）和 --emit-sql（只出 SQL 文件、不连库执行）。

前提：先在新库跑过 V12 迁移（photo_project 表存在）。

用法示例
--------
  # 1) 离线出报告和计划（不连库）
  python restore_project_photos.py plan \
      -i ../photowarehouse/photowarehouse-export.json -o restore-plan.json

  # 2) 对着生产库预演（只数不写）
  python restore_project_photos.py apply --plan restore-plan.json \
      -i ../photowarehouse/photowarehouse-export.json \
      --target-url "mysql://user:pass@host:3306/photolib" --dry-run

  # 3) 生成可人工审阅执行的 SQL（仍需 --target-url 翻译 id）
  python restore_project_photos.py apply --plan restore-plan.json \
      -i ../photowarehouse/photowarehouse-export.json \
      --target-url "mysql://user:pass@host:3306/photolib" --emit-sql restore.sql

  # 4) 真正写库（幂等，可重跑）
  python restore_project_photos.py apply --plan restore-plan.json \
      -i ../photowarehouse/photowarehouse-export.json \
      --target-url "mysql://user:pass@host:3306/photolib"
"""
from __future__ import annotations

import argparse
import base64
import csv
import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path


# --------------------------------------------------------------------------- #
# JSON 包解码（与 migrate_photowarehouse.py 的编码格式保持一致）
# --------------------------------------------------------------------------- #
def decode_value(v):
    """把导出包里的 {"$type":...,"value":...} 还原成 Python 值。"""
    if not isinstance(v, dict) or "$type" not in v:
        return v
    kind = v["$type"]
    raw = v.get("value")
    if raw is None:
        return None
    if kind == "datetime":
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            return raw
    if kind == "decimal":
        return Decimal(raw)
    if kind == "bytes":
        return base64.b64decode(raw)
    return raw


def load_tables(path: Path) -> dict[str, list[dict]]:
    """读取导出包，返回 {表名: [已解码的行, ...]}。"""
    doc = json.loads(path.read_text(encoding="utf-8"))
    tables: dict[str, list[dict]] = {}
    for name, rows in doc.get("tables", {}).items():
        tables[name] = [
            {k: decode_value(val) for k, val in row.items()} for row in rows
        ]
    return tables


# --------------------------------------------------------------------------- #
# object key 前缀改写（必须与迁移时用的规则一致，apply 端反查照片要用）
# --------------------------------------------------------------------------- #
def remap_key(key: str | None, source_prefix: str, destination_prefix: str) -> str | None:
    """把旧对象 key 改写到新前缀；两个前缀都为空时保持不变。与迁移脚本同逻辑。"""
    if not key:
        return key
    sp, dp = source_prefix.strip("/"), destination_prefix.strip("/")
    if sp and not (key == sp or key.startswith(sp + "/")):
        return key
    relative = key[len(sp):].lstrip("/") if sp else key.lstrip("/")
    return f"{dp}/{relative}" if dp else relative


# --------------------------------------------------------------------------- #
# 核心：从导出包推导「项目 -> 照片」归属
# --------------------------------------------------------------------------- #
def derive_membership(tables: dict[str, list[dict]]) -> dict:
    """按“照片标签 ⊇ 项目标签”推导归属。返回一份纯旧 id 的计划 + 统计。"""
    def rows(t):
        return tables.get(t, [])

    tag_name = {int(t["id"]): t.get("name") for t in rows("tags")}
    projects = {int(p["id"]): p for p in rows("projects")}

    project_tags: dict[int, set[int]] = defaultdict(set)
    for r in rows("project_tags"):
        if r.get("project_id") is not None and r.get("tag_id") is not None:
            project_tags[int(r["project_id"])].add(int(r["tag_id"]))

    photo_tags: dict[int, set[int]] = defaultdict(set)
    for r in rows("photo_tags"):
        if r.get("photo_id") is not None and r.get("tag_id") is not None:
            photo_tags[int(r["photo_id"])].add(int(r["tag_id"]))

    # project -> [photo_id...]，photo -> [project_id...]
    project_photos: dict[int, list[int]] = {}
    photo_projects: dict[int, list[int]] = defaultdict(list)
    for pid in sorted(projects):
        ptags = project_tags.get(pid, set())
        if not ptags:
            project_photos[pid] = []  # 无标签的项目在旧系统里本就空展示
            continue
        members = sorted(
            phid for phid, phtags in photo_tags.items() if ptags <= phtags
        )
        project_photos[pid] = members
        for phid in members:
            photo_projects[phid].append(pid)

    ambiguous = {ph: pids for ph, pids in photo_projects.items() if len(pids) > 1}

    summary = []
    for pid in sorted(projects):
        ptags = project_tags.get(pid, set())
        summary.append({
            "old_project_id": pid,
            "title": projects[pid].get("title"),
            "tag_ids": sorted(ptags),
            "tag_names": [tag_name.get(t, f"#{t}") for t in sorted(ptags)],
            "photo_count": len(project_photos[pid]),
            "sample_photo_id": projects[pid].get("sample_photo_id"),
        })

    return {
        "projects": summary,
        "project_photos": {str(k): v for k, v in project_photos.items()},
        "photo_projects": {str(k): v for k, v in photo_projects.items()},
        "ambiguous_photos": {str(k): v for k, v in ambiguous.items()},
        "stats": {
            "projects_total": len(projects),
            "projects_with_tags": sum(1 for pid in projects if project_tags.get(pid)),
            "photos_total": len(rows("photos")),
            "photos_assigned": len(photo_projects),
            "photos_ambiguous": len(ambiguous),
        },
    }


def membership_pairs(photo_projects: dict[str, list[int]]) -> list[tuple[int, int]]:
    """把 {old_photo: [old_project...]} 摊平成全部 (old_photo, old_project) 归属对。

    一图多项目：不做压缩，每条归属都保留——新库 photo_project 是多对多表。
    """
    pairs: list[tuple[int, int]] = []
    for ph_str, pids in photo_projects.items():
        for pid in sorted(set(pids)):
            pairs.append((int(ph_str), int(pid)))
    return sorted(pairs)


# --------------------------------------------------------------------------- #
# plan：离线出报告
# --------------------------------------------------------------------------- #
def cmd_plan(a) -> None:
    tables = load_tables(Path(a.input))
    plan = derive_membership(tables)
    plan["params"] = {
        "generated_at": datetime.now().isoformat(),
        "input": str(a.input),
    }

    Path(a.output).write_text(
        json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    # 人类可读的两份 CSV：项目汇总 + 多归属明细
    report_csv = Path(a.output).with_suffix(".projects.csv")
    with report_csv.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["old_project_id", "title", "tag_names", "photo_count", "sample_photo_id"])
        for p in plan["projects"]:
            w.writerow([p["old_project_id"], p["title"],
                        "/".join(p["tag_names"]), p["photo_count"], p["sample_photo_id"]])

    amb_csv = Path(a.output).with_suffix(".ambiguous.csv")
    with amb_csv.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["old_photo_id", "matched_old_project_ids"])
        for ph, pids in sorted(plan["ambiguous_photos"].items(), key=lambda x: int(x[0])):
            w.writerow([ph, "/".join(map(str, pids))])

    s = plan["stats"]
    print("== 归属推导完成（离线，未连库）==", flush=True)
    print(f"  项目总数: {s['projects_total']}（有标签: {s['projects_with_tags']}）")
    print(f"  照片总数: {s['photos_total']}")
    print(f"  被归入至少一个项目的照片: {s['photos_assigned']}")
    print(f"  多归属（属于>1个项目）的照片: {s['photos_ambiguous']}")
    print("\n  每个项目将获得的照片数：")
    for p in plan["projects"]:
        tags = "/".join(p["tag_names"]) or "(无标签)"
        print(f"    项目#{p['old_project_id']:>3} [{tags}] -> {p['photo_count']:>4} 张  | {p['title']}")
    print(f"\n  计划: {a.output}")
    print(f"  项目汇总: {report_csv}")
    print(f"  多归属明细: {amb_csv}")
    if s["photos_ambiguous"]:
        print(f"\n  注意：{s['photos_ambiguous']} 张照片同时归属多个项目；新库 photo_project 是多对多表，"
              f"apply 会为每条归属各写一行（这些照片会在多个项目相册中出现），明细见 ambiguous.csv。")


# --------------------------------------------------------------------------- #
# apply：连新库写回
# --------------------------------------------------------------------------- #
def parse_mysql_url(url: str) -> dict:
    """把 mysql://user:pass@host:port/db 或 jdbc:mysql://... 解析成 pymysql 连接参数。"""
    import re
    from urllib.parse import unquote

    raw = url
    for pre in ("jdbc:mysql://", "mysql+pymysql://", "mysql://"):
        if raw.startswith(pre):
            raw = raw[len(pre):]
            break
    # 去掉 ?参数
    raw = raw.split("?", 1)[0]
    m = re.match(r"^(?:([^:@/]+)(?::([^@/]*))?@)?([^:/]+)(?::(\d+))?/(.+)$", raw)
    if not m:
        raise RuntimeError(f"无法解析 MySQL 连接串: {url}")
    user, pw, host, port, db = m.groups()
    return {
        "host": host,
        "port": int(port) if port else 3306,
        "user": unquote(user) if user else "root",
        "password": unquote(pw) if pw else "",
        "database": db,
        "charset": "utf8mb4",
    }


def build_id_maps(cur, tables, needed_old_photos, needed_old_projects,
                  source_prefix, destination_prefix):
    """构建 旧photo->新photo、旧project->新project 的 id 映射。

    优先用 legacy_migration_item；缺失的照片再用 object_key 反查兜底、项目用 title 兜底。
    返回 (photo_map, project_map, diagnostics)。
    """
    photos_by_id = {int(p["id"]): p for p in tables.get("photos", [])}
    projects_by_id = {int(p["id"]): p for p in tables.get("projects", [])}

    # legacy_migration_item
    photo_map: dict[int, int] = {}
    project_map: dict[int, int] = {}
    try:
        cur.execute("SELECT source_type, source_id, target_id FROM legacy_migration_item")
        for stype, sid, tid in cur.fetchall():
            if stype == "PHOTO":
                photo_map[int(sid)] = int(tid)
            elif stype == "PROJECT":
                project_map[int(sid)] = int(tid)
    except Exception as e:  # 表不存在等
        print(f"[提示] 读取 legacy_migration_item 失败({e})，改用兜底匹配。", file=sys.stderr)

    diag = {"photo_via_map": len(photo_map), "project_via_map": len(project_map),
            "photo_via_key": 0, "photo_unresolved": 0,
            "project_via_title": 0, "project_unresolved": 0}

    # 照片兜底：object_key 反查
    for oid in needed_old_photos:
        if oid in photo_map:
            continue
        p = photos_by_id.get(oid)
        new_key = remap_key(p.get("file_path") if p else None, source_prefix, destination_prefix)
        if new_key:
            cur.execute("SELECT id FROM photo WHERE object_key=%s AND deleted=FALSE", (new_key,))
            row = cur.fetchone()
            if row:
                photo_map[oid] = int(row[0])
                diag["photo_via_key"] += 1
                continue
        diag["photo_unresolved"] += 1

    # 项目兜底：按 title 精确匹配（migrate 写入时 title 原样，无对应时才用“旧项目 {id}”）
    for oid in needed_old_projects:
        if oid in project_map:
            continue
        p = projects_by_id.get(oid)
        title = (p.get("title") if p else None) or f"旧项目 {oid}"
        cur.execute("SELECT id FROM project WHERE title=%s AND deleted=FALSE", (title[:200],))
        found = cur.fetchall()
        if len(found) == 1:
            project_map[oid] = int(found[0][0])
            diag["project_via_title"] += 1
        else:
            diag["project_unresolved"] += 1
            print(f"[警告] 旧项目#{oid}《{title}》在新库中"
                  f"{'匹配到多个同名' if len(found) > 1 else '找不到对应'}，已跳过。",
                  file=sys.stderr)

    return photo_map, project_map, diag


def cmd_apply(a) -> None:
    # 计划来源：优先 --plan；否则现算。apply 始终需要导出包（用于 id 兜底匹配）。
    if a.plan:
        plan = json.loads(Path(a.plan).read_text(encoding="utf-8"))
    else:
        if not a.input:
            raise RuntimeError("未提供 --plan 时必须用 -i 指定导出包")
        plan = derive_membership(load_tables(Path(a.input)))
    if not a.input:
        raise RuntimeError("apply 需要 -i 指定导出包（用于 id 兜底匹配）")
    tables = load_tables(Path(a.input))

    pairs = membership_pairs(plan["photo_projects"])  # 全部 (旧照片, 旧项目) 归属对
    print(f"待恢复归属对（一图多项目，不压缩）: {len(pairs)} 条", flush=True)
    if not pairs:
        print("没有可恢复的归属，结束。")
        return

    import pymysql

    if not a.target_url:
        raise RuntimeError("apply/--emit-sql 都需要 --target-url：翻译旧 id 到新库 id 必须查映射表/新库。")

    conn = pymysql.connect(**parse_mysql_url(a.target_url))
    try:
        cur = conn.cursor()
        needed_old_photos = {op for op, _ in pairs}
        needed_old_projects = {opr for _, opr in pairs}
        photo_map, project_map, diag = build_id_maps(
            cur, tables, needed_old_photos, needed_old_projects,
            a.source_prefix, a.destination_prefix)
        print("id 映射情况:", json.dumps(diag, ensure_ascii=False), flush=True)

        # 翻译成 (新photo_id, 新project_id, 旧photo, 旧project)
        links: list[tuple[int, int, int, int]] = []
        skipped_no_map = 0
        for old_photo, old_project in pairs:
            np = photo_map.get(old_photo)
            npr = project_map.get(old_project)
            if np is None or npr is None:
                skipped_no_map += 1
                continue
            links.append((np, npr, old_photo, old_project))
        print(f"可写入链接: {len(links)} 条；因缺映射跳过: {skipped_no_map} 条", flush=True)

        # SQL 文件模式：产出 INSERT INTO photo_project（幂等，脚本仅连 MySQL）
        if a.emit_sql:
            lines = ["-- 由 restore_project_photos.py 生成；恢复 photo_project 归属（一图多项目）",
                     f"-- 条目数: {len(links)}",
                     "START TRANSACTION;"]
            for np, npr, op, opr in links:
                lines.append(
                    f"INSERT INTO photo_project (photo_id, project_id) VALUES ({np}, {npr}) "
                    f"ON DUPLICATE KEY UPDATE photo_id=photo_id; "
                    f"-- old photo#{op} -> old project#{opr}")
            lines.append("COMMIT;")
            Path(a.emit_sql).write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"已写出 SQL: {a.emit_sql}（未执行）", flush=True)
            return

        # 统计将新增的链接行数（已存在的不计）
        would_insert = 0
        for np, npr, op, opr in links:
            cur.execute(
                "SELECT 1 FROM photo_project WHERE photo_id=%s AND project_id=%s", (np, npr))
            if cur.fetchone() is None:
                would_insert += 1

        if a.dry_run:
            print(f"[dry-run] 将新增 {would_insert} 条归属链接（已存在的不重复）。未写库。", flush=True)
            return

        inserted = 0
        for np, npr, op, opr in links:
            cur.execute(
                "INSERT INTO photo_project (photo_id, project_id) VALUES (%s, %s) "
                "ON DUPLICATE KEY UPDATE photo_id=photo_id", (np, npr))
            inserted += cur.rowcount  # 新增行 rowcount=1，重复行=0
        conn.commit()
        print(f"[已提交] 新增 {inserted} 条归属链接（重复的已跳过）。", flush=True)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="按旧系统标签规则恢复项目照片归属（写入多对多表 photo_project）")
    sub = p.add_subparsers(dest="command", required=True)

    pl = sub.add_parser("plan", help="离线：读导出包，推导归属，出报告(不连库)")
    pl.add_argument("--input", "-i", required=True, help="photowarehouse-export.json 路径")
    pl.add_argument("--output", "-o", default="restore-plan.json", help="计划输出路径")

    ap = sub.add_parser("apply", help="连新库：翻译 id 并写入 photo_project 归属")
    ap.add_argument("--input", "-i", help="导出包路径（id 兜底匹配需要，建议始终提供）")
    ap.add_argument("--plan", help="plan 生成的计划 JSON；不给则现算")
    ap.add_argument("--target-url", default=os.getenv("NEW_DATABASE_URL"),
                    help="新 MySQL 连接串，或环境变量 NEW_DATABASE_URL")
    ap.add_argument("--dry-run", action="store_true", help="只统计将新增的链接行数，不写库")
    ap.add_argument("--emit-sql", help="只生成可人工执行的 .sql 文件（仍需 --target-url 翻译 id）")
    ap.add_argument("--source-prefix", default=os.getenv("OLD_OSS_PREFIX", ""),
                    help="旧对象 key 前缀（object_key 兜底匹配用，须与迁移时一致）")
    ap.add_argument("--destination-prefix", default=os.getenv("NEW_OSS_PREFIX", ""),
                    help="新对象 key 前缀（object_key 兜底匹配用，须与迁移时一致）")
    return p


def main():
    a = build_parser().parse_args()
    if a.command == "plan":
        cmd_plan(a)
    elif a.command == "apply":
        cmd_apply(a)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(f"执行失败: {e}", file=sys.stderr)
        raise
