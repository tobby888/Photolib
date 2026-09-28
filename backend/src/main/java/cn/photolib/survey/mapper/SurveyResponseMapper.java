package cn.photolib.survey.mapper;

import cn.photolib.common.util.LikeFilter;
import cn.photolib.survey.model.SurveyResponseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 答卷查询。{@code campusIds} 为空表示不按校区收窄；校区范围的查看人传入自己的授权校区，
 * 只看得到这些校区成员交的答卷（和统计导出用同一套 {@code scopedCampusIds()} 口径）。
 */
@Mapper
public interface SurveyResponseMapper extends BaseMapper<SurveyResponseEntity> {
    String FILTER = """
            <if test="keyword != null and keyword != ''">
              AND (LOWER(u.display_name) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!'
                   OR LOWER(u.username) LIKE CONCAT('%', LOWER(#{keyword}), '%') ESCAPE '!')
            </if>
            <if test="campusIds != null and campusIds.size() > 0">
              AND EXISTS (SELECT 1 FROM user_campus_permission ucp
                          WHERE ucp.user_id=r.user_id AND ucp.campus_id IN
                          <foreach collection="campusIds" item="campusId" open="(" separator="," close=")">#{campusId}</foreach>)
            </if>
            """;

    String SELECT = """
            SELECT r.*, u.display_name AS display_name, u.username AS username,
                   pg.name AS permission_group_name
            FROM survey_response r
            JOIN app_user u ON u.id=r.user_id
            LEFT JOIN permission_group pg ON pg.id=COALESCE(u.permission_group_id,
                (SELECT legacy_pg.id FROM permission_group legacy_pg WHERE legacy_pg.code=u.role))
            """;

    @Select("<script>" + SELECT + " WHERE r.survey_id=#{surveyId} " + FILTER + """
            ORDER BY r.submitted_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SurveyResponseEntity> findPageQuery(@Param("surveyId") long surveyId,
                                             @Param("keyword") String keyword,
                                             @Param("campusIds") Collection<Long> campusIds,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM survey_response r JOIN app_user u ON u.id=r.user_id"
            + " WHERE r.survey_id=#{surveyId} " + FILTER + "</script>")
    long countQuery(@Param("surveyId") long surveyId,
                    @Param("keyword") String keyword,
                    @Param("campusIds") Collection<Long> campusIds);

    default List<SurveyResponseEntity> findPage(long surveyId, String keyword, Collection<Long> campusIds,
                                                int limit, long offset) {
        return findPageQuery(surveyId, LikeFilter.escape(keyword), campusIds, limit, offset);
    }

    default long count(long surveyId, String keyword, Collection<Long> campusIds) {
        return countQuery(surveyId, LikeFilter.escape(keyword), campusIds);
    }

    @Select(SELECT + " WHERE r.id=#{id}")
    SurveyResponseEntity findDetail(@Param("id") long id);

    @Select("SELECT * FROM survey_response WHERE survey_id=#{surveyId} AND user_id=#{userId}")
    SurveyResponseEntity findByUser(@Param("surveyId") long surveyId, @Param("userId") long userId);
}
