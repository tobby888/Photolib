package cn.photolib.survey.mapper;

import cn.photolib.common.util.LikeFilter;
import cn.photolib.survey.model.SurveyEntity;
import cn.photolib.survey.model.SurveyStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SurveyMapper extends BaseMapper<SurveyEntity> {
    String VIEW_COLUMNS = """
            SELECT s.*, u.display_name AS creator_display_name,
                   (SELECT COUNT(*) FROM survey_target t WHERE t.survey_id=s.id) AS target_count,
                   (SELECT COUNT(*) FROM survey_response r WHERE r.survey_id=s.id) AS response_count
            FROM survey s
            JOIN app_user u ON u.id=s.created_by
            """;

    @Select("""
            <script>
            """ + VIEW_COLUMNS + """
            WHERE s.deleted=FALSE
            <if test="!includeDrafts">AND s.status &lt;&gt; 'DRAFT'</if>
            <if test="status != null">AND s.status=#{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (LOWER(s.title) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(COALESCE(s.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            ORDER BY s.created_at DESC, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SurveyEntity> findPageQuery(@Param("includeDrafts") boolean includeDrafts,
                                     @Param("status") SurveyStatus status,
                                     @Param("keyword") String keyword,
                                     @Param("limit") int limit,
                                     @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM survey s
            WHERE s.deleted=FALSE
            <if test="!includeDrafts">AND s.status &lt;&gt; 'DRAFT'</if>
            <if test="status != null">AND s.status=#{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (LOWER(s.title) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(COALESCE(s.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            </script>
            """)
    long countPageQuery(@Param("includeDrafts") boolean includeDrafts,
                        @Param("status") SurveyStatus status,
                        @Param("keyword") String keyword);

    /** 关键词按 {@code ESCAPE '!'} 转义后再进 LIKE，否则搜 "%" 会匹配全部问卷。 */
    default List<SurveyEntity> findPage(boolean includeDrafts, SurveyStatus status, String keyword,
                                        int limit, long offset) {
        return findPageQuery(includeDrafts, status, LikeFilter.escape(keyword), limit, offset);
    }

    default long countPage(boolean includeDrafts, SurveyStatus status, String keyword) {
        return countPageQuery(includeDrafts, status, LikeFilter.escape(keyword));
    }

    @Select(VIEW_COLUMNS + " WHERE s.id=#{id} AND s.deleted=FALSE")
    SurveyEntity findViewById(@Param("id") long id);

    @Select("SELECT * FROM survey WHERE id=#{id} AND deleted=FALSE FOR UPDATE")
    SurveyEntity findByIdForUpdate(@Param("id") long id);

    /** 发给某人、已经发布过（进行中或已结束）的问卷。 */
    @Select("""
            SELECT s.*, u.display_name AS creator_display_name
            FROM survey s
            JOIN survey_target t ON t.survey_id=s.id AND t.user_id=#{userId}
            JOIN app_user u ON u.id=s.created_by
            WHERE s.deleted=FALSE AND s.status <> 'DRAFT'
            ORDER BY s.published_at DESC, s.id DESC
            LIMIT 500
            """)
    List<SurveyEntity> findAssignedTo(@Param("userId") long userId);
}
