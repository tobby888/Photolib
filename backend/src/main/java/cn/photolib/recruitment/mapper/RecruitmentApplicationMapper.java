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
            SELECT * FROM recruitment_application
            WHERE task_id=#{taskId}
            ORDER BY submitted_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<RecruitmentApplicationEntity> findByTask(@Param("taskId") long taskId,
                                                   @Param("limit") int limit,
                                                   @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM recruitment_application WHERE task_id=#{taskId}")
    long countByTask(@Param("taskId") long taskId);
}
