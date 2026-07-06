package cn.photolib.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
    @Select("""
            <script>
            SELECT a.id, a.operator_id, u.username AS operator_username,
                   u.display_name AS operator_display_name, a.action,
                   a.resource_type, a.resource_id, a.request_id, a.detail_json,
                   a.ip_address, a.created_at
            FROM audit_log a
            LEFT JOIN app_user u ON u.id = a.operator_id
            <where>
              <if test="operatorId != null">AND a.operator_id = #{operatorId}</if>
              <if test="action != null and action != ''">AND a.action = #{action}</if>
              <if test="resourceType != null and resourceType != ''">AND a.resource_type = #{resourceType}</if>
              <if test="from != null">AND a.created_at &gt;= #{from}</if>
              <if test="to != null">AND a.created_at &lt; #{to}</if>
              <if test="keyword != null and keyword != ''">
                AND (LOWER(u.username) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(u.display_name) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(a.resource_id) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(a.request_id) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(a.ip_address) LIKE LOWER(CONCAT('%', #{keyword}, '%')))
              </if>
            </where>
            ORDER BY a.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditLogView> findLogs(@Param("operatorId") Long operatorId,
                                @Param("action") String action,
                                @Param("resourceType") String resourceType,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("keyword") String keyword,
                                @Param("limit") long limit,
                                @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM audit_log a
            LEFT JOIN app_user u ON u.id = a.operator_id
            <where>
              <if test="operatorId != null">AND a.operator_id = #{operatorId}</if>
              <if test="action != null and action != ''">AND a.action = #{action}</if>
              <if test="resourceType != null and resourceType != ''">AND a.resource_type = #{resourceType}</if>
              <if test="from != null">AND a.created_at &gt;= #{from}</if>
              <if test="to != null">AND a.created_at &lt; #{to}</if>
              <if test="keyword != null and keyword != ''">
                AND (LOWER(u.username) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(u.display_name) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(a.resource_id) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(a.request_id) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                  OR LOWER(a.ip_address) LIKE LOWER(CONCAT('%', #{keyword}, '%')))
              </if>
            </where>
            </script>
            """)
    long countLogs(@Param("operatorId") Long operatorId,
                   @Param("action") String action,
                   @Param("resourceType") String resourceType,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to,
                   @Param("keyword") String keyword);
}
