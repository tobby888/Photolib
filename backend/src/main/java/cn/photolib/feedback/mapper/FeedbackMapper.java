package cn.photolib.feedback.mapper;

import cn.photolib.feedback.model.FeedbackEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedbackMapper extends BaseMapper<FeedbackEntity> {

    /**
     * 列表：ADMIN 传 {@code submitterId = null} 看全量，普通成员传自己的 id 只看自己的。
     * {@code status} 可选，为空不过滤。提交人显示名一次 join 出来。
     */
    @Select("""
            <script>
            SELECT f.*, u.display_name AS submitter_display_name
            FROM feedback f
            JOIN app_user u ON u.id = f.submitter_id
            WHERE 1 = 1
            <if test="submitterId != null">AND f.submitter_id = #{submitterId}</if>
            <if test="status != null">AND f.status = #{status}</if>
            ORDER BY f.created_at DESC, f.id DESC
            </script>
            """)
    List<FeedbackEntity> list(@Param("submitterId") Long submitterId, @Param("status") String status);

    @Select("""
            SELECT f.*, u.display_name AS submitter_display_name
            FROM feedback f
            JOIN app_user u ON u.id = f.submitter_id
            WHERE f.id = #{id}
            """)
    FeedbackEntity findById(@Param("id") long id);

    @Select("""
            SELECT COUNT(*) FROM feedback
            WHERE submitter_id = #{userId} AND created_at >= #{since}
            """)
    long countSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

    @Update("""
            UPDATE feedback
            SET status = #{status}, version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND version = #{version}
            """)
    int updateStatus(@Param("id") long id, @Param("status") String status,
                     @Param("version") int version, @Param("now") LocalDateTime now);
}
