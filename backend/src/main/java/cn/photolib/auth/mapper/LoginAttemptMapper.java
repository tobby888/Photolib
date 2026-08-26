package cn.photolib.auth.mapper;

import cn.photolib.auth.model.LoginAttemptEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface LoginAttemptMapper extends BaseMapper<LoginAttemptEntity> {

    @Select("""
            SELECT * FROM login_attempt
            WHERE scope=#{scope} AND attempt_key=#{attemptKey}
            """)
    LoginAttemptEntity find(@Param("scope") String scope,
                            @Param("attemptKey") String attemptKey);

    /**
     * Counts one failure, restarting the window when the previous one has already
     * elapsed. Doing it in a single statement lets the unique index settle
     * concurrent attempts on the same key instead of losing counts to a
     * read-then-write race — the shape a password-guessing run produces.
     */
    @Update("""
            UPDATE login_attempt
            SET failure_count = CASE WHEN first_failed_at < #{windowStart} THEN 1
                                     ELSE failure_count + 1 END,
                first_failed_at = CASE WHEN first_failed_at < #{windowStart} THEN #{now}
                                       ELSE first_failed_at END,
                last_failed_at = #{now},
                locked_until = CASE
                    WHEN (CASE WHEN first_failed_at < #{windowStart} THEN 1
                               ELSE failure_count + 1 END) >= #{threshold} THEN #{lockUntil}
                    ELSE locked_until END
            WHERE scope=#{scope} AND attempt_key=#{attemptKey}
            """)
    int countFailure(@Param("scope") String scope,
                     @Param("attemptKey") String attemptKey,
                     @Param("now") LocalDateTime now,
                     @Param("windowStart") LocalDateTime windowStart,
                     @Param("threshold") int threshold,
                     @Param("lockUntil") LocalDateTime lockUntil);

    @Delete("""
            DELETE FROM login_attempt
            WHERE scope=#{scope} AND attempt_key=#{attemptKey}
            """)
    int clear(@Param("scope") String scope, @Param("attemptKey") String attemptKey);

    /** Drops rows whose window and lock have both elapsed, so the table stays bounded. */
    @Delete("""
            DELETE FROM login_attempt
            WHERE last_failed_at < #{before}
              AND (locked_until IS NULL OR locked_until < #{now})
            """)
    int purgeSettled(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);
}
