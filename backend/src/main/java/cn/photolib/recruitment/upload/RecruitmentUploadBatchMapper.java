package cn.photolib.recruitment.upload;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecruitmentUploadBatchMapper extends BaseMapper<RecruitmentUploadBatchEntity> {
    @Select("""
            SELECT id FROM recruitment_upload_batch
            WHERE draft_id=#{draftId} AND status<>'FAILED' FOR UPDATE
            """)
    List<String> lockNonFailedByDraft(@Param("draftId") String draftId);

    @Select("""
            SELECT COUNT(*) FROM recruitment_upload_batch
            WHERE draft_id=#{draftId} AND status IN ('UPLOADING','PROCESSING')
            """)
    long countInProgressByDraft(@Param("draftId") String draftId);

    @Select("""
            SELECT id FROM recruitment_upload_batch
            WHERE status='PROCESSING' ORDER BY updated_at, id LIMIT #{limit}
            """)
    List<String> findProcessingIds(@Param("limit") int limit);

    @Select("""
            SELECT * FROM recruitment_upload_batch
            WHERE archive_object_key IS NOT NULL AND upload_url_expires_at<=#{now}
              AND status<>'PROCESSING'
            ORDER BY upload_url_expires_at, id LIMIT #{limit}
            """)
    List<RecruitmentUploadBatchEntity> findExpiredArchiveTargets(
            @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE recruitment_upload_batch
            SET status=#{next}, updated_at=#{now}
            WHERE id=#{id} AND draft_id=#{draftId} AND status=#{expected}
            """)
    int transition(@Param("id") String id, @Param("draftId") String draftId,
                   @Param("expected") RecruitmentUploadBatchStatus expected,
                   @Param("next") RecruitmentUploadBatchStatus next,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET status='FAILED', failure_reason=#{failureReason}, updated_at=#{now}
            WHERE id=#{id} AND draft_id=#{draftId} AND status='UPLOADING'
            """)
    int failInvalidZip(@Param("id") String id, @Param("draftId") String draftId,
                       @Param("failureReason") String failureReason,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET status=#{next}, total_count=#{totalCount}, success_count=#{successCount},
                failure_count=#{failureCount}, failure_reason=#{failureReason}, updated_at=#{now}
            WHERE id=#{id} AND status='PROCESSING'
            """)
    int finish(@Param("id") String id,
               @Param("next") RecruitmentUploadBatchStatus next,
               @Param("totalCount") int totalCount,
               @Param("successCount") int successCount,
               @Param("failureCount") int failureCount,
               @Param("failureReason") String failureReason,
               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET archive_object_key=NULL, updated_at=#{now}
            WHERE id=#{id} AND archive_object_key=#{objectKey}
            """)
    int clearArchiveObjectKey(@Param("id") String id, @Param("objectKey") String objectKey,
                              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET archive_object_key=NULL,
                failure_reason=CASE WHEN status IN ('UPLOADING','PROCESSING')
                    THEN '上传地址已过期，请重新上传' ELSE failure_reason END,
                status=CASE WHEN status IN ('UPLOADING','PROCESSING') THEN 'FAILED' ELSE status END,
                updated_at=#{now}
            WHERE id=#{id} AND archive_object_key=#{objectKey}
              AND upload_url_expires_at<=#{now}
            """)
    int clearExpiredArchiveTarget(@Param("id") String id,
                                  @Param("objectKey") String objectKey,
                                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET status='FAILED', failure_reason='上传地址已过期，请重新上传', updated_at=#{now}
            WHERE id=#{id} AND mode='FILES' AND status='UPLOADING'
              AND NOT EXISTS (SELECT 1 FROM recruitment_upload_item i
                  WHERE i.batch_id=#{id} AND i.temp_object_key IS NOT NULL)
            """)
    int failExpiredFileBatchWithoutSources(@Param("id") String id,
                                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET archive_object_key=NULL, status='FAILED',
                failure_reason='招募草稿已过期，上传内容已清理', updated_at=#{now}
            WHERE id=#{id} AND draft_id=#{draftId} AND archive_object_key=#{objectKey}
            """)
    int clearExpiredArchive(@Param("id") String id, @Param("draftId") String draftId,
                            @Param("objectKey") String objectKey,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_batch
            SET status='FAILED', failure_reason='招募草稿已过期，上传内容已清理', updated_at=#{now}
            WHERE draft_id=#{draftId} AND status IN ('UPLOADING','PROCESSING')
            """)
    int failInProgressForExpiredDraft(@Param("draftId") String draftId,
                                      @Param("now") LocalDateTime now);
}
