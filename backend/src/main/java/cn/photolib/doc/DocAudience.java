package cn.photolib.doc;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.doc.model.DocVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 文档与文件的读者范围：判定和"指定权限组 / 指定成员"名单的读写，两边共用这一份。
 *
 * <p>三档范围（{@link DocVisibility}）：</p>
 * <ul>
 *   <li>{@code PUBLIC}——任何人，包括未登录访客；</li>
 *   <li>{@code MEMBERS}——登录即可；</li>
 *   <li>{@code RESTRICTED}——必须登录，并且属于名单里的某个权限组，或者本人就在名单里。</li>
 * </ul>
 *
 * <p>判定只有 {@link #allows} 一处。文档的目录、正文、插图、PDF 直链，文件的列表和下载，
 * 都必须过它——谁另写一份，谁就成了那道被漏掉的门。编辑者的"看得到自己管的东西"
 * 不在这里，由各自的调用方在外面叠加（文档看 DOC_MANAGE / DOC_PUBLISH，文件看上传者和
 * FILE_MANAGE），因为两边的编辑权限不一样。</p>
 */
@Component
@RequiredArgsConstructor
public class DocAudience {
    /** 名单上限：这是给人手挑的名单，不是群发；再多就该新建一个权限组。 */
    static final int MAX_GROUPS = 50;
    static final int MAX_USERS = 500;

    private final JdbcClient jdbc;

    public enum ResourceType {
        DOC,
        FILE
    }

    /** 一个资源的读者名单。只有 {@code RESTRICTED} 时才有内容。 */
    public record Grants(Set<Long> groupIds, Set<Long> userIds) {
        public static final Grants NONE = new Grants(Set.of(), Set.of());

        public Grants {
            groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
            userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
        }

        public boolean isEmpty() {
            return groupIds.isEmpty() && userIds.isEmpty();
        }
    }

    /**
     * 读者范围判定的唯一实现。{@code visibility} 为 null（脏数据）时按 MEMBERS 处理：
     * 宁可少给人看，也不当成公开。
     */
    public static boolean allows(DocVisibility visibility, Grants grants, DocReader reader) {
        if (visibility == DocVisibility.PUBLIC) return true;
        if (reader == null || !reader.authenticated()) return false;
        if (visibility != DocVisibility.RESTRICTED) return true;
        Grants list = grants == null ? Grants.NONE : grants;
        return list.userIds().contains(reader.userId())
                || (reader.groupId() != null && list.groupIds().contains(reader.groupId()));
    }

    public Grants load(ResourceType type, long resourceId) {
        Map<Long, Grants> loaded = loadFor(type, List.of(resourceId));
        return loaded.getOrDefault(resourceId, Grants.NONE);
    }

    /** 一类资源的全部名单。文档树最多 1000 个节点，整表读进来比逐个查便宜。 */
    public Map<Long, Grants> loadAll(ResourceType type) {
        return group(jdbc.sql("""
                        SELECT resource_id, grantee_type, grantee_id FROM resource_access_grant
                        WHERE resource_type=:type
                        """).param("type", type.name())
                .query((rs, row) -> new Row(rs.getLong("resource_id"), rs.getString("grantee_type"),
                        rs.getLong("grantee_id")))
                .list());
    }

    public Map<Long, Grants> loadFor(ResourceType type, Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) return Map.of();
        return group(jdbc.sql("""
                        SELECT resource_id, grantee_type, grantee_id FROM resource_access_grant
                        WHERE resource_type=:type AND resource_id IN (:ids)
                        """).param("type", type.name()).param("ids", List.copyOf(resourceIds))
                .query((rs, row) -> new Row(rs.getLong("resource_id"), rs.getString("grantee_type"),
                        rs.getLong("grantee_id")))
                .list());
    }

    /**
     * 校验并整理一份名单：只有 RESTRICTED 需要名单，而且至少一个组或一个人——
     * 空名单的"指定读者"谁也读不到，几乎一定是忘了选。其余两档一律清空名单，
     * 免得从 RESTRICTED 改成 MEMBERS 再改回来时，旧名单悄悄复活。
     */
    public Grants normalize(DocVisibility visibility, Collection<Long> groupIds, Collection<Long> userIds) {
        if (visibility != DocVisibility.RESTRICTED) return Grants.NONE;
        Set<Long> groups = clean(groupIds);
        Set<Long> users = clean(userIds);
        if (groups.isEmpty() && users.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "「指定成员」至少要选一个权限组或一位成员");
        }
        if (groups.size() > MAX_GROUPS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "最多指定 " + MAX_GROUPS + " 个权限组");
        }
        if (users.size() > MAX_USERS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "最多单独指定 " + MAX_USERS + " 位成员，人更多时请改用权限组");
        }
        if (!groups.isEmpty() && count("SELECT COUNT(*) FROM permission_group WHERE deleted=FALSE AND id IN (:ids)",
                groups) != groups.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "选择的权限组不存在或已被删除");
        }
        if (!users.isEmpty() && count("SELECT COUNT(*) FROM app_user WHERE deleted=FALSE AND id IN (:ids)",
                users) != users.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "选择的成员不存在或已被删除");
        }
        return new Grants(groups, users);
    }

    /** 整份替换一个资源的名单。调用方先 {@link #normalize}，并在同一个事务里。 */
    public void replace(ResourceType type, long resourceId, Grants grants) {
        jdbc.sql("DELETE FROM resource_access_grant WHERE resource_type=:type AND resource_id=:id")
                .param("type", type.name()).param("id", resourceId).update();
        Grants list = grants == null ? Grants.NONE : grants;
        for (Long groupId : new TreeSet<>(list.groupIds())) insert(type, resourceId, "GROUP", groupId);
        for (Long userId : new TreeSet<>(list.userIds())) insert(type, resourceId, "USER", userId);
    }

    /** 挑选名单用的候选：全部权限组，以及全部启用中的账号（带所在权限组，方便按组辨认）。 */
    public Options options() {
        List<GroupOption> groups = jdbc.sql("""
                        SELECT g.id, g.name,
                               (SELECT COUNT(*) FROM app_user u
                                WHERE u.permission_group_id=g.id AND u.deleted=FALSE AND u.enabled=TRUE) AS members
                        FROM permission_group g
                        WHERE g.deleted=FALSE
                        ORDER BY g.built_in DESC, g.id ASC
                        """)
                .query((rs, row) -> new GroupOption(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("members")))
                .list();
        List<UserOption> users = jdbc.sql("""
                        SELECT u.id, u.display_name, u.username, u.permission_group_id, g.name AS group_name
                        FROM app_user u
                        LEFT JOIN permission_group g ON g.id=u.permission_group_id AND g.deleted=FALSE
                        WHERE u.deleted=FALSE AND u.enabled=TRUE
                        ORDER BY u.display_name ASC, u.id ASC
                        """)
                .query((rs, row) -> new UserOption(rs.getLong("id"), rs.getString("display_name"),
                        rs.getString("username"), (Long) rs.getObject("permission_group_id", Long.class),
                        rs.getString("group_name")))
                .list();
        return new Options(groups, users);
    }

    private void insert(ResourceType type, long resourceId, String granteeType, long granteeId) {
        jdbc.sql("""
                INSERT INTO resource_access_grant(resource_type, resource_id, grantee_type, grantee_id)
                VALUES (:type, :id, :granteeType, :granteeId)
                """).param("type", type.name()).param("id", resourceId)
                .param("granteeType", granteeType).param("granteeId", granteeId).update();
    }

    private long count(String sql, Set<Long> ids) {
        Long value = jdbc.sql(sql).param("ids", List.copyOf(ids)).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private static Set<Long> clean(Collection<Long> ids) {
        Set<Long> result = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) if (id != null && id > 0) result.add(id);
        }
        return result;
    }

    private static Map<Long, Grants> group(List<Row> rows) {
        Map<Long, Set<Long>> groups = new HashMap<>();
        Map<Long, Set<Long>> users = new HashMap<>();
        for (Row row : rows) {
            Map<Long, Set<Long>> target = "GROUP".equals(row.granteeType()) ? groups : users;
            target.computeIfAbsent(row.resourceId(), key -> new LinkedHashSet<>()).add(row.granteeId());
        }
        Map<Long, Grants> result = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>(groups.keySet());
        ids.addAll(users.keySet());
        for (Long id : ids) {
            result.put(id, new Grants(groups.getOrDefault(id, Set.of()), users.getOrDefault(id, Set.of())));
        }
        return result;
    }

    private record Row(long resourceId, String granteeType, long granteeId) {
    }

    public record GroupOption(Long id, String name, long memberCount) {
    }

    public record UserOption(Long id, String displayName, String username, Long permissionGroupId,
                             String permissionGroupName) {
    }

    public record Options(List<GroupOption> groups, List<UserOption> users) {
    }
}
