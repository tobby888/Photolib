package cn.photolib.recruitment.mapper;

import cn.photolib.common.util.LikeFilter;
import cn.photolib.recruitment.model.RecruitmentTaskEntity;
import cn.photolib.recruitment.model.RecruitmentTaskStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecruitmentTaskMapper extends BaseMapper<RecruitmentTaskEntity> {
    @Select("""
            <script>
            SELECT t.*, u.display_name AS creator_display_name,
                   (SELECT COUNT(*) FROM recruitment_application a WHERE a.task_id=t.id) AS application_count
            FROM recruitment_task t
            JOIN app_user u ON u.id=t.created_by
            WHERE t.deleted=FALSE
            <if test="status != null">AND t.status=#{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (LOWER(t.title) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(COALESCE(t.intro_markdown, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RecruitmentTaskEntity> findPageQuery(@Param("status") RecruitmentTaskStatus status,
                                              @Param("keyword") String keyword,
                                              @Param("limit") int limit,
                                              @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM recruitment_task t
            WHERE t.deleted=FALSE
            <if test="status != null">AND t.status=#{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (LOWER(t.title) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(COALESCE(t.intro_markdown, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            </script>
            """)
    long countPageQuery(@Param("status") RecruitmentTaskStatus status,
                        @Param("keyword") String keyword);

    /**
     * Lists tasks, optionally narrowed by a title/intro fragment. The statements
     * declare {@code ESCAPE '!'}, so the fragment has to arrive already escaped —
     * an operator searching for a literal {@code %} or {@code _} would otherwise
     * silently match every task.
     */
    default List<RecruitmentTaskEntity> findPage(RecruitmentTaskStatus status, String keyword,
                                                 int limit, long offset) {
        return findPageQuery(status, LikeFilter.escape(keyword), limit, offset);
    }

    default long countPage(RecruitmentTaskStatus status, String keyword) {
        return countPageQuery(status, LikeFilter.escape(keyword));
    }

    @Select("""
            SELECT t.*, u.display_name AS creator_display_name,
                   (SELECT COUNT(*) FROM recruitment_application a WHERE a.task_id=t.id) AS application_count
            FROM recruitment_task t
            JOIN app_user u ON u.id=t.created_by
            WHERE t.id=#{id} AND t.deleted=FALSE
            """)
    RecruitmentTaskEntity findViewById(@Param("id") long id);

    @Select("""
            SELECT * FROM recruitment_task
            WHERE public_id=#{publicId} AND deleted=FALSE
            LIMIT 1
            """)
    RecruitmentTaskEntity findByPublicId(@Param("publicId") String publicId);

    @Select("""
            SELECT * FROM recruitment_task
            WHERE id=#{id} AND deleted=FALSE
            FOR UPDATE
            """)
    RecruitmentTaskEntity findByIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT * FROM recruitment_task
            WHERE public_id=#{publicId} AND deleted=FALSE
            LIMIT 1 FOR UPDATE
            """)
    RecruitmentTaskEntity findByPublicIdForUpdate(@Param("publicId") String publicId);

    @Select("""
            SELECT * FROM recruitment_task
            WHERE deleted=FALSE AND status='PUBLISHED'
              AND starts_at <= #{now} AND ends_at > #{now}
            ORDER BY starts_at ASC, id ASC
            """)
    List<RecruitmentTaskEntity> findActive(@Param("now") LocalDateTime now);
}
