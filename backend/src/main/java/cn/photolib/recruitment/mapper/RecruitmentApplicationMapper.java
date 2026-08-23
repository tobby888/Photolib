package cn.photolib.recruitment.mapper;

import cn.photolib.recruitment.model.RecruitmentApplicationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecruitmentApplicationMapper extends BaseMapper<RecruitmentApplicationEntity> {
    @Select("""
            SELECT COUNT(*) FROM recruitment_application
            WHERE task_id=#{taskId} AND normalized_student_id=#{studentId}
            """)
    long countByStudent(@Param("taskId") long taskId,
                        @Param("studentId") String normalizedStudentId);

    @Select("""
            <script>
            SELECT * FROM recruitment_application
            WHERE task_id=#{taskId}
            <if test="studentId != null and studentId != ''">
              AND normalized_student_id LIKE CONCAT('%', #{studentId}, '%') ESCAPE '!'
            </if>
            ORDER BY submitted_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RecruitmentApplicationEntity> findByTaskQuery(@Param("taskId") long taskId,
                                                       @Param("studentId") String normalizedStudentId,
                                                       @Param("limit") int limit,
                                                       @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM recruitment_application
            WHERE task_id=#{taskId}
            <if test="studentId != null and studentId != ''">
              AND normalized_student_id LIKE CONCAT('%', #{studentId}, '%') ESCAPE '!'
            </if>
            </script>
            """)
    long countByTaskQuery(@Param("taskId") long taskId,
                          @Param("studentId") String normalizedStudentId);

    /**
     * Lists a task's applications, optionally narrowed to identifiers containing
     * {@code normalizedStudentId}. The fragment must already be normalized the way
     * {@code normalized_student_id} is stored — see
     * {@code RecruitmentStudentId.normalizeSearchFragment}.
     */
    default List<RecruitmentApplicationEntity> findByTask(long taskId, String normalizedStudentId,
                                                          int limit, long offset) {
        return findByTaskQuery(taskId, escapeLike(normalizedStudentId), limit, offset);
    }

    default long countByTask(long taskId, String normalizedStudentId) {
        return countByTaskQuery(taskId, escapeLike(normalizedStudentId));
    }

    /**
     * Underscore is both a legal student-identifier character and a single-character
     * LIKE wildcard, so an unescaped fragment would silently over-match.
     */
    private static String escapeLike(String value) {
        return value == null ? null : value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
