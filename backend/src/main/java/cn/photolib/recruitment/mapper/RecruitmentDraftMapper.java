package cn.photolib.recruitment.mapper;

import cn.photolib.recruitment.model.RecruitmentDraftEntity;
import cn.photolib.recruitment.model.RecruitmentDraftStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RecruitmentDraftMapper extends BaseMapper<RecruitmentDraftEntity> {
    @Select("SELECT * FROM recruitment_draft WHERE id=#{id} FOR UPDATE")
    RecruitmentDraftEntity findByIdForUpdate(@Param("id") String id);

    @Update("""
            UPDATE recruitment_draft
            SET status=#{next}, updated_at=#{now}
            WHERE id=#{id} AND status=#{expected}
            """)
    int transition(@Param("id") String id,
                   @Param("expected") RecruitmentDraftStatus expected,
                   @Param("next") RecruitmentDraftStatus next,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_draft
            SET expires_at=#{expiresAt}, updated_at=#{now}
            WHERE task_id=#{taskId} AND status='DRAFT'
            """)
    int synchronizeOpenDraftExpiry(@Param("taskId") long taskId,
                                   @Param("expiresAt") LocalDateTime expiresAt,
                                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_draft
            SET status='EXPIRED', expires_at=#{now}, updated_at=#{now}
            WHERE task_id=#{taskId} AND status='DRAFT'
            """)
    int expireOpenDrafts(@Param("taskId") long taskId, @Param("now") LocalDateTime now);
}
