package cn.photolib.recruitment.upload;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecruitmentUploadCleanupMapper {
    @Select("""
            SELECT d.id FROM recruitment_draft d
            WHERE (d.status='DRAFT' AND d.expires_at<=#{now})
               OR d.status='CLEANUP_PENDING'
               OR (d.status='EXPIRED' AND EXISTS (
                    SELECT 1 FROM recruitment_upload_batch b
                    WHERE b.draft_id=d.id
                      AND (b.archive_object_key IS NOT NULL OR EXISTS (
                          SELECT 1 FROM recruitment_upload_item i
                          WHERE i.batch_id=b.id
                            AND (i.temp_object_key IS NOT NULL OR i.object_key IS NOT NULL)))))
            ORDER BY d.expires_at, d.id
            LIMIT #{limit}
            """)
    List<String> findCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE recruitment_draft SET status='CLEANUP_PENDING', updated_at=#{now}
            WHERE id=#{draftId} AND status='DRAFT' AND expires_at<=#{now}
            """)
    int claim(@Param("draftId") String draftId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_draft SET status='CLEANUP_PENDING', updated_at=#{now}
            WHERE id=#{draftId} AND status='EXPIRED'
              AND EXISTS (SELECT 1 FROM recruitment_upload_batch b
                  WHERE b.draft_id=#{draftId}
                    AND (b.archive_object_key IS NOT NULL OR EXISTS (
                        SELECT 1 FROM recruitment_upload_item i
                        WHERE i.batch_id=b.id
                          AND (i.temp_object_key IS NOT NULL OR i.object_key IS NOT NULL))))
            """)
    int claimExpired(@Param("draftId") String draftId, @Param("now") LocalDateTime now);

    @Select("SELECT status FROM recruitment_draft WHERE id=#{draftId}")
    String status(@Param("draftId") String draftId);

    @Update("""
            UPDATE recruitment_upload_item
            SET status='FAILED', failure_reason='招募草稿已过期，上传内容已清理', updated_at=#{now}
            WHERE batch_id IN (SELECT id FROM recruitment_upload_batch WHERE draft_id=#{draftId})
            """)
    int failItems(@Param("draftId") String draftId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET status='FAILED', success_count=0, failure_count=total_count,
                failure_reason='招募草稿已过期，上传内容已清理', updated_at=#{now}
            WHERE draft_id=#{draftId}
            """)
    int failBatches(@Param("draftId") String draftId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM recruitment_upload_batch b
            JOIN recruitment_upload_item i ON i.batch_id=b.id
            WHERE b.draft_id=#{draftId}
              AND i.object_key IS NOT NULL
            """)
    long countRemainingObjectKeys(@Param("draftId") String draftId);

    @Update("""
            UPDATE recruitment_draft SET status='EXPIRED', updated_at=#{now}
            WHERE id=#{draftId} AND status='CLEANUP_PENDING'
            """)
    int finish(@Param("draftId") String draftId, @Param("now") LocalDateTime now);
}
