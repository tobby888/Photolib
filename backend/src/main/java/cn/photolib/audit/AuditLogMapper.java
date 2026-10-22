package cn.photolib.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 列表和计数的 WHERE 必须完全一致，否则分页总数会错。两条语句里的条件块是逐字复制的，
 * 改一处就同步改另一处（{@code AuditLogMapperTests} 会比对两者的结果）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
    @Select("""
            <script>
            SELECT a.id, a.operator_id, u.username AS operator_username,
                   u.display_name AS operator_display_name, u.role AS operator_role,
                   g.name AS operator_group_name, a.action, a.action_name,
                   a.resource_type, a.resource_id, a.resource_name, a.request_id,
                   a.http_status, a.success, a.error_code, a.error_message, a.detail_json,
                   a.ip_address, a.user_agent, a.duration_ms, a.created_at
            FROM audit_log a
            LEFT JOIN app_user u ON u.id = a.operator_id
            LEFT JOIN permission_group g ON g.id = u.permission_group_id
            <where>
              <if test="f.operatorId != null">AND a.operator_id = #{f.operatorId}</if>
              <if test="f.action != null and f.action != ''">AND a.action = #{f.action}</if>
              <if test="f.actionName != null and f.actionName != ''">AND a.action_name = #{f.actionName}</if>
              <if test="f.resourceType != null and f.resourceType != ''">AND a.resource_type = #{f.resourceType}</if>
              <if test="f.success != null">AND a.success = #{f.success}</if>
              <if test="f.ipAddress != null and f.ipAddress != ''">AND a.ip_address = #{f.ipAddress}</if>
              <if test="f.from != null">AND a.created_at &gt;= #{f.from}</if>
              <if test="f.to != null">AND a.created_at &lt; #{f.to}</if>
              <if test="f.keyword != null and f.keyword != ''">
                AND (LOWER(u.username) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(u.display_name) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.action_name) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.resource_id) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.resource_name) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.request_id) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.error_message) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.ip_address) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!')
              </if>
            </where>
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditLogView> findLogs(@Param("f") AuditLogFilter filter,
                                @Param("limit") long limit,
                                @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM audit_log a
            LEFT JOIN app_user u ON u.id = a.operator_id
            LEFT JOIN permission_group g ON g.id = u.permission_group_id
            <where>
              <if test="f.operatorId != null">AND a.operator_id = #{f.operatorId}</if>
              <if test="f.action != null and f.action != ''">AND a.action = #{f.action}</if>
              <if test="f.actionName != null and f.actionName != ''">AND a.action_name = #{f.actionName}</if>
              <if test="f.resourceType != null and f.resourceType != ''">AND a.resource_type = #{f.resourceType}</if>
              <if test="f.success != null">AND a.success = #{f.success}</if>
              <if test="f.ipAddress != null and f.ipAddress != ''">AND a.ip_address = #{f.ipAddress}</if>
              <if test="f.from != null">AND a.created_at &gt;= #{f.from}</if>
              <if test="f.to != null">AND a.created_at &lt; #{f.to}</if>
              <if test="f.keyword != null and f.keyword != ''">
                AND (LOWER(u.username) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(u.display_name) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.action_name) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.resource_id) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.resource_name) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.request_id) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.error_message) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!'
                  OR LOWER(a.ip_address) LIKE LOWER(CONCAT('%', #{f.keyword}, '%')) ESCAPE '!')
              </if>
            </where>
            </script>
            """)
    long countLogs(@Param("f") AuditLogFilter filter);

    /** 出现过的操作名，给筛选下拉框补上目录里没有的（老版本接口的名字）。 */
    @Select("SELECT DISTINCT action_name FROM audit_log WHERE action_name IS NOT NULL")
    List<String> distinctActionNames();
}
