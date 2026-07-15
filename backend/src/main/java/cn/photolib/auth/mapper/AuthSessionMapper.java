package cn.photolib.auth.mapper;

import cn.photolib.auth.model.AuthSessionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AuthSessionMapper extends BaseMapper<AuthSessionEntity> {
    @Update("""
            UPDATE auth_session
            SET revoked_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND revoked_at IS NULL AND idle_expires_at > #{now}
            """)
    int revokeActive(@Param("id") Long id, @Param("now") LocalDateTime now);
}
