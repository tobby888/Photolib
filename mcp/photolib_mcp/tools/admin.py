"""系统管理：账号、权限组、审计、告警、品牌与数据库备份。

这一组里绝大多数工具都要求管理员。它们能改的是"谁能做什么"和整库数据，
所以每个写操作都应当先把改动内容复述给用户、拿到明确确认再执行——
尤其是重置密码、删除账号、改权限组和数据库回滚。
"""

from __future__ import annotations

from typing import Any, Literal

from ..toolkit import ToolRegistry, compact, page_params
from ..transfers import download_to_path, save_bytes

UserRole = Literal["ADMIN", "MINISTER", "CAMPUS_MANAGER"]
DataScope = Literal["NONE", "CAMPUS", "GLOBAL"]
PhotoVisibility = Literal["SELF", "CAMPUS", "GLOBAL"]


def register(registry: ToolRegistry) -> None:
    session = registry.session

    # ---- 账号 -----------------------------------------------------------

    @registry.tool("users_list", tags=("admin",))
    async def users_list(page: int = 1, page_size: int = 20, keyword: str | None = None,
                         role: UserRole | None = None, permission_group_id: int | None = None,
                         campus_id: int | None = None,
                         enabled: bool | None = None) -> dict[str, Any]:
        """分页查询系统账号（仅管理员）。"""
        return await session.request("GET", "/users", params=page_params(
            page, page_size, keyword=keyword, role=role,
            permissionGroupId=permission_group_id, campusId=campus_id, enabled=enabled))

    @registry.tool("users_get", tags=("admin",))
    async def users_get(user_id: int) -> dict[str, Any]:
        """账号详情。管理员可以查任何人，普通成员只能查自己。"""
        return await session.request("GET", f"/users/{user_id}")

    @registry.tool("users_create", write=True, tags=("admin",))
    async def users_create(username: str, display_name: str, permission_group_id: int | None = None,
                           role: UserRole | None = None, campus_id: int | None = None,
                           campus_ids: list[int] | None = None, phone: str | None = None,
                           email: str | None = None,
                           wecom_userid: str | None = None) -> dict[str, Any]:
        """新建账号（仅管理员）。返回里带初始密码，**只会给这一次**。

        初始密码要交给本人，并让他首次登录后立刻修改。不要把它写进任何共享的地方。
        """
        return await session.request("POST", "/users", json_body=compact({
            "username": username, "displayName": display_name, "role": role,
            "campusId": campus_id, "permissionGroupId": permission_group_id,
            "campusIds": campus_ids, "phone": phone, "email": email,
            "wecomUserid": wecom_userid,
        }))

    @registry.tool("users_update", write=True, tags=("admin",))
    async def users_update(user_id: int, display_name: str, enabled: bool, version: int,
                           role: UserRole | None = None, campus_id: int | None = None,
                           permission_group_id: int | None = None,
                           campus_ids: list[int] | None = None, phone: str | None = None,
                           email: str | None = None,
                           wecom_userid: str | None = None) -> dict[str, Any]:
        """修改账号（仅管理员）。全量覆盖，先 photolib_users_get 读一遍再改。"""
        return await session.request("PUT", f"/users/{user_id}", json_body=compact({
            "displayName": display_name, "role": role, "campusId": campus_id,
            "permissionGroupId": permission_group_id, "campusIds": campus_ids,
            "phone": phone, "email": email, "wecomUserid": wecom_userid,
            "enabled": enabled, "version": version,
        }))

    @registry.tool("users_set_authorization", write=True, tags=("admin",))
    async def users_set_authorization(user_id: int, permission_group_id: int,
                                      campus_ids: list[int], version: int) -> dict[str, Any]:
        """改一个账号的权限组和授权校区（仅管理员）。

        这是直接改"这个人能做什么"。改完立刻生效，先确认新权限组的含义。
        """
        return await session.request("PUT", f"/users/{user_id}/authorization", json_body={
            "permissionGroupId": permission_group_id, "campusIds": campus_ids, "version": version,
        })

    @registry.tool("users_reset_password", write=True, tags=("admin",))
    async def users_reset_password(user_id: int) -> dict[str, Any]:
        """重置某个账号的密码（仅管理员），返回新的初始密码。

        这会让该账号所有会话立刻失效。请先确认是本人提出的请求，
        新密码只能交给他本人。
        """
        return await session.request("PUT", f"/users/{user_id}/password")

    @registry.tool("users_set_enabled", write=True, tags=("admin",))
    async def users_set_enabled(user_id: int, enabled: bool) -> dict[str, Any]:
        """启用或停用一个账号（仅管理员）。停用会让他的会话立刻失效。"""
        action = "enable" if enabled else "disable"
        return await session.request("POST", f"/users/{user_id}/{action}")

    @registry.tool("users_delete", write=True, tags=("admin",))
    async def users_delete(user_id: int) -> dict[str, Any]:
        """删除一个账号（仅管理员）。不能删自己，也不能删掉最后一个启用的管理员。

        删除是不可逆的。执行前务必让用户确认是哪一个账号。
        """
        await session.request("DELETE", f"/users/{user_id}")
        return {"status": "DELETED", "userId": user_id}

    # ---- 权限组 ---------------------------------------------------------

    @registry.tool("permission_groups_list", tags=("admin",))
    async def permission_groups_list() -> list[dict[str, Any]]:
        """权限组列表（仅管理员）。"""
        return await session.request("GET", "/permission-groups")

    @registry.tool("permission_groups_get", tags=("admin",))
    async def permission_groups_get(group_id: int) -> dict[str, Any]:
        """权限组详情。"""
        return await session.request("GET", f"/permission-groups/{group_id}")

    @registry.tool("permission_groups_definitions", tags=("admin",))
    async def permission_groups_definitions() -> list[dict[str, Any]]:
        """所有权限码及其分类说明。建权限组之前先看它，别猜权限码。"""
        return await session.request("GET", "/permission-groups/definitions")

    @registry.tool("permission_groups_create", write=True, tags=("admin",))
    async def permission_groups_create(code: str, name: str, data_scope: DataScope,
                                       photo_visibility: PhotoVisibility,
                                       permissions: list[str],
                                       description: str | None = None) -> dict[str, Any]:
        """新建权限组（仅管理员）。

        `permissions` 是权限码数组，取值见 photolib_permission_groups_definitions。
        `data_scope` 决定能看到哪些校区的数据，`photo_visibility` 单独决定图库可见范围。
        """
        return await session.request("POST", "/permission-groups", json_body=compact({
            "code": code, "name": name, "description": description,
            "dataScope": data_scope, "photoVisibility": photo_visibility,
            "permissions": permissions,
        }))

    @registry.tool("permission_groups_update", write=True, tags=("admin",))
    async def permission_groups_update(group_id: int, name: str, data_scope: DataScope,
                                       photo_visibility: PhotoVisibility,
                                       permissions: list[str], version: int,
                                       description: str | None = None) -> dict[str, Any]:
        """修改权限组（仅管理员）。

        权限清单是**整体替换**：没列进来的权限会被收回，而且对组里所有成员立刻生效。
        改之前先读一遍现有清单，并把增减项复述给用户确认。
        """
        return await session.request("PUT", f"/permission-groups/{group_id}", json_body=compact({
            "name": name, "description": description, "dataScope": data_scope,
            "photoVisibility": photo_visibility, "permissions": permissions, "version": version,
        }))

    @registry.tool("permission_groups_delete", write=True, tags=("admin",))
    async def permission_groups_delete(group_id: int) -> dict[str, Any]:
        """删除权限组（仅管理员）。还有成员在用的组删不掉。"""
        await session.request("DELETE", f"/permission-groups/{group_id}")
        return {"status": "DELETED", "groupId": group_id}

    # ---- 审计与告警 -----------------------------------------------------

    @registry.tool("audit_logs_list", tags=("admin",))
    async def audit_logs_list(page: int = 1, page_size: int = 20, operator_id: int | None = None,
                              action: str | None = None, resource_type: str | None = None,
                              keyword: str | None = None, date_from: str | None = None,
                              date_to: str | None = None) -> dict[str, Any]:
        """分页查询审计日志（仅管理员）。

        只记写操作。`date_to` 那一天整天都包含在内。
        """
        return await session.request("GET", "/audit-logs", params=page_params(
            page, page_size, operatorId=operator_id, action=action,
            resourceType=resource_type, keyword=keyword,
            **{"from": date_from, "to": date_to}))

    @registry.tool("audit_logs_export", write=True, tags=("admin",))
    async def audit_logs_export(target_path: str, operator_id: int | None = None,
                                action: str | None = None, resource_type: str | None = None,
                                keyword: str | None = None, date_from: str | None = None,
                                date_to: str | None = None) -> dict[str, Any]:
        """把审计日志导成 CSV 存到本地（仅管理员，最多 10 万条）。"""
        response = await session.raw_request(
            "GET", "/audit-logs/export", accept="text/csv",
            params={"operatorId": operator_id, "action": action, "resourceType": resource_type,
                    "keyword": keyword, "from": date_from, "to": date_to},
            timeout=session.settings.transfer_timeout)
        return save_bytes(response.content, target_path, "audit-logs.csv")

    @registry.tool("admin_alerts_list", tags=("admin",))
    async def admin_alerts_list(resolved: bool = False) -> list[dict[str, Any]]:
        """系统告警（仅管理员）。默认只看未处理的。"""
        return await session.request("GET", "/admin-alerts", params={"resolved": resolved})

    @registry.tool("admin_alerts_resolve", write=True, tags=("admin",))
    async def admin_alerts_resolve(alert_id: int) -> dict[str, Any]:
        """把一条告警标记为已处理（仅管理员）。"""
        await session.request("POST", f"/admin-alerts/{alert_id}/resolve")
        return {"status": "RESOLVED", "alertId": alert_id}

    # ---- 品牌 -----------------------------------------------------------

    @registry.tool("branding_get", tags=("admin",))
    async def branding_get() -> dict[str, Any]:
        """当前的品牌设置（标题、图标、标语、登录页与页脚文案）。这一条不需要登录。"""
        return await session.request("GET", "/branding",
                                     authenticated=session.credentials() is not None)

    @registry.tool("branding_update", write=True, tags=("admin",))
    async def branding_update(title: str, icon_type: str, builtin_icon: str,
                              slogan: str) -> dict[str, Any]:
        """改站点标题、图标与标语（仅管理员）。

        `icon_type` 只能是 `builtin` 或 `custom`；`builtin_icon` 要是系统支持的图标名，
        先读 photolib_branding_get 看现在用的是哪个。这些改动全站所有人都会立刻看到。
        """
        return await session.request("PUT", "/branding", json_body={
            "title": title, "iconType": icon_type, "builtinIcon": builtin_icon, "slogan": slogan,
        })

    @registry.tool("branding_update_site", write=True, tags=("admin",))
    async def branding_update_site(login_headline: str | None = None,
                                   login_subheadline: str | None = None,
                                   login_highlights: list[str] | None = None,
                                   login_notice: str | None = None,
                                   footer_text: str | None = None,
                                   footer_links: list[dict[str, str]] | None = None) -> dict[str, Any]:
        """改登录页文案与页脚（仅管理员）。

        这是全量覆盖：没传的字段会被清空。`footer_links` 的元素形如
        `{"label": "…", "url": "…"}`。
        """
        return await session.request("PUT", "/branding/site", json_body={
            "loginHeadline": login_headline, "loginSubheadline": login_subheadline,
            "loginHighlights": login_highlights, "loginNotice": login_notice,
            "footerText": footer_text, "footerLinks": footer_links,
        })

    @registry.tool("branding_upload_icon", write=True, tags=("admin",))
    async def branding_upload_icon(file_path: str) -> dict[str, Any]:
        """上传自定义站点图标（仅管理员）。"""
        return await session.request("POST", "/branding/icon", file_path=file_path)

    @registry.tool("branding_scheduled_icons", tags=("admin",))
    async def branding_scheduled_icons() -> list[dict[str, Any]]:
        """按时间自动切换的图标规则（仅管理员）。"""
        return await session.request("GET", "/branding/scheduled-icons")

    @registry.tool("branding_placeholder_images", tags=("admin",))
    async def branding_placeholder_images() -> list[dict[str, Any]]:
        """图片加载失败时显示的占位图（仅管理员）。"""
        return await session.request("GET", "/branding/placeholder-images")

    @registry.tool("branding_upload_placeholder_image", write=True, tags=("admin",))
    async def branding_upload_placeholder_image(file_path: str) -> list[dict[str, Any]]:
        """上传一张占位图（仅管理员）。"""
        return await session.request("POST", "/branding/placeholder-images",
                                     file_path=file_path, file_field="files")

    @registry.tool("branding_delete_placeholder_image", write=True, tags=("admin",))
    async def branding_delete_placeholder_image(image_id: int) -> list[dict[str, Any]]:
        """删掉一张占位图（仅管理员）。"""
        return await session.request("DELETE", f"/branding/placeholder-images/{image_id}")

    # ---- 数据库备份与回滚 -------------------------------------------------

    @registry.tool("database_backups_list", tags=("admin",))
    async def database_backups_list(page: int = 1, page_size: int = 20) -> dict[str, Any]:
        """数据库备份列表（仅管理员）。"""
        return await session.request("GET", "/database-backups",
                                     params=page_params(page, page_size))

    @registry.tool("database_backups_get", tags=("admin",))
    async def database_backups_get(backup_id: str) -> dict[str, Any]:
        """一次备份的详情与状态。"""
        return await session.request("GET", f"/database-backups/{backup_id}")

    @registry.tool("database_backups_create", write=True, tags=("admin",))
    async def database_backups_create() -> dict[str, Any]:
        """立刻做一次手动备份（仅管理员）。备份是异步的，用 get 查状态。"""
        return await session.request("POST", "/database-backups")

    @registry.tool("database_backups_download", write=True, tags=("admin",))
    async def database_backups_download(backup_id: str, target_path: str) -> dict[str, Any]:
        """把一份备份下载到本地（仅管理员）。

        备份文件包含全库数据。落到哪台机器上要想清楚，不要放进会被同步或共享的目录。
        """
        link = await session.request("GET", f"/database-backups/{backup_id}/download")
        return await download_to_path(session, link["downloadUrl"], target_path,
                                      default_name=link.get("fileName") or f"{backup_id}.sql.gz")

    @registry.tool("database_backups_upload", write=True, tags=("admin",))
    async def database_backups_upload(file_path: str) -> dict[str, Any]:
        """导入一份外部备份文件（仅管理员）。只是入库备案，不会自动回滚。"""
        return await session.request("POST", "/database-backups/upload", file_path=file_path)

    @registry.tool("database_backups_restore", write=True, tags=("admin",))
    async def database_backups_restore(backup_id: str) -> dict[str, Any]:
        """用某份备份回滚整个数据库（仅管理员）。

        **这是本服务里破坏性最强的操作**：它会用备份覆盖现有数据。绝不要主动建议，
        也不要顺着一句含糊的话就执行。必须由用户明确说出"用这份备份回滚数据库"，
        并且你已经把备份的时间和 id 复述给他确认过。回滚前系统会自动先做一次兜底备份。
        """
        return await session.request("POST", f"/database-backups/{backup_id}/restore")

    @registry.tool("database_restores_list", tags=("admin",))
    async def database_restores_list(page: int = 1, page_size: int = 20) -> dict[str, Any]:
        """回滚记录（仅管理员）。"""
        return await session.request("GET", "/database-restores",
                                     params=page_params(page, page_size))

    @registry.tool("database_restores_get", tags=("admin",))
    async def database_restores_get(restore_id: str) -> dict[str, Any]:
        """一次回滚的详情与状态。"""
        return await session.request("GET", f"/database-restores/{restore_id}")
