package cn.photolib.recruitment.upload;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecruitmentUploadItemMapper extends BaseMapper<RecruitmentUploadItemEntity> {
    @Select("""
            SELECT i.* FROM recruitment_upload_item i
            JOIN recruitment_upload_batch b ON b.id=i.batch_id
            WHERE b.draft_id=#{draftId} AND i.status='SUCCEEDED' AND i.object_key IS NOT NULL
            ORDER BY i.id
            """)
    List<RecruitmentUploadItemEntity> findSucceededByDraft(@Param("draftId") String draftId);

    @Select("""
            SELECT * FROM recruitment_upload_item
            WHERE temp_object_key IS NOT NULL AND upload_url_expires_at<=#{now}
              AND status<>'PROCESSING'
            ORDER BY upload_url_expires_at, id LIMIT #{limit}
            """)
    List<RecruitmentUploadItemEntity> findExpiredTemporaryTargets(
            @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT * FROM recruitment_upload_item
            WHERE object_key IS NOT NULL AND status='FAILED'
            ORDER BY updated_at, id LIMIT #{limit}
            """)
    List<RecruitmentUploadItemEntity> findAbandonedFinalTargets(@Param("limit") int limit);

    @Update("""
            UPDATE recruitment_upload_item
            SET status='PROCESSING', updated_at=#{now}
            WHERE batch_id=#{batchId} AND status='UPLOADING'
            """)
    int claimAll(@Param("batchId") String batchId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET status=#{next}, updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND status=#{expected}
            """)
    int transition(@Param("id") Long id, @Param("batchId") String batchId,
                   @Param("expected") RecruitmentUploadItemStatus expected,
                   @Param("next") RecruitmentUploadItemStatus next,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item SET object_key=#{objectKey}, updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND status='PROCESSING'
              AND object_key IS NULL
            """)
    int reserveObjectKey(@Param("id") Long id, @Param("batchId") String batchId,
                         @Param("objectKey") String objectKey,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET object_key=#{objectKey}, size=#{size}, sha256=#{sha256}, status='SUCCEEDED',
                failure_reason=NULL, updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND status='PROCESSING'
              AND object_key=#{objectKey}
            """)
    int succeed(@Param("id") Long id, @Param("batchId") String batchId,
                @Param("objectKey") String objectKey, @Param("size") long size,
                @Param("sha256") String sha256, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET status='FAILED', failure_reason=#{failureReason}, updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND status IN ('UPLOADING','PROCESSING')
            """)
    int fail(@Param("id") Long id, @Param("batchId") String batchId,
             @Param("failureReason") String failureReason, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET temp_object_key=NULL, updated_at=#{now}
            WHERE id=#{id} AND temp_object_key=#{objectKey}
            """)
    int clearTempObjectKey(@Param("id") Long id, @Param("objectKey") String objectKey,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET temp_object_key=NULL,
                failure_reason=CASE WHEN status IN ('UPLOADING','PROCESSING')
                    THEN '上传地址已过期，请重新上传' ELSE failure_reason END,
                status=CASE WHEN status IN ('UPLOADING','PROCESSING') THEN 'FAILED' ELSE status END,
                updated_at=#{now}
            WHERE id=#{id} AND temp_object_key=#{objectKey}
              AND upload_url_expires_at<=#{now}
            """)
    int clearExpiredTemporaryTarget(@Param("id") Long id,
                                    @Param("objectKey") String objectKey,
                                    @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item SET object_key=NULL, updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND object_key=#{objectKey}
              AND status<>'SUCCEEDED'
            """)
    int clearReservedObjectKey(@Param("id") Long id, @Param("batchId") String batchId,
                               @Param("objectKey") String objectKey,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item SET object_key=#{objectKey}, updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND status='FAILED'
              AND object_key IS NULL
            """)
    int restoreAbandonedObjectKey(@Param("id") Long id, @Param("batchId") String batchId,
                                  @Param("objectKey") String objectKey,
                                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET temp_object_key=NULL, status='FAILED',
                failure_reason='招募草稿已过期，上传内容已清理', updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND temp_object_key=#{objectKey}
            """)
    int clearExpiredTempObject(@Param("id") Long id, @Param("batchId") String batchId,
                               @Param("objectKey") String objectKey,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recruitment_upload_item
            SET object_key=NULL, status='FAILED',
                failure_reason='招募草稿已过期，上传内容已清理', updated_at=#{now}
            WHERE id=#{id} AND batch_id=#{batchId} AND object_key=#{objectKey}
            """)
    int clearExpiredFinalObject(@Param("id") Long id, @Param("batchId") String batchId,
                                @Param("objectKey") String objectKey,
                                @Param("now") LocalDateTime now);
}
