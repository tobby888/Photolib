package cn.photolib.mcp.mapper;

import cn.photolib.mcp.model.McpAuthRequestEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 状态迁移一律写成"带条件的 UPDATE + 检查影响行数"，而不是先查后写：
 * 批准页和客户端轮询是两个并发的调用者，读到的对象一转身就可能过期。
 * 只有让数据库来判定前置状态，"一条记录只能换一次令牌"才真的成立。
 */
public interface McpAuthRequestMapper extends BaseMapper<McpAuthRequestEntity> {

    @Update("""
            UPDATE mcp_auth_request
            SET status = 'APPROVED', user_id = #{userId}, approved_at = #{now}, updated_at = #{now}
            WHERE request_id = #{requestId} AND status = 'PENDING' AND expires_at > #{now}
            """)
    int approve(@Param("requestId") String requestId, @Param("userId") Long userId,
                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mcp_auth_request
            SET status = 'DENIED', user_id = #{userId}, updated_at = #{now}
            WHERE request_id = #{requestId} AND status = 'PENDING' AND expires_at > #{now}
            """)
    int deny(@Param("requestId") String requestId, @Param("userId") Long userId,
             @Param("now") LocalDateTime now);

    /** 取走令牌。只有它返回 1 的那个调用者才会拿到令牌，重复轮询拿不到第二份。 */
    @Update("""
            UPDATE mcp_auth_request
            SET status = 'CONSUMED', consumed_at = #{now}, updated_at = #{now}
            WHERE request_id = #{requestId} AND status = 'APPROVED' AND expires_at > #{now}
            """)
    int consume(@Param("requestId") String requestId, @Param("now") LocalDateTime now);

    /**
     * 记一次配对码猜错。超过上限就地作废，不留给调用方"查出来再判断"的空档——
     * 那个空档正好够并发的猜测请求挤进来。
     */
    @Update("""
            UPDATE mcp_auth_request
            SET failed_attempts = failed_attempts + 1,
                status = CASE WHEN failed_attempts + 1 >= #{maxAttempts} THEN 'DENIED' ELSE status END,
                updated_at = #{now}
            WHERE request_id = #{requestId} AND status = 'PENDING'
            """)
    int recordFailedAttempt(@Param("requestId") String requestId,
                            @Param("maxAttempts") int maxAttempts,
                            @Param("now") LocalDateTime now);

    /** 过期记录的清理。留出一段宽限期，好让客户端还能读到"已过期"而不是"不存在"。 */
    @Delete("DELETE FROM mcp_auth_request WHERE expires_at < #{cutoff}")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
