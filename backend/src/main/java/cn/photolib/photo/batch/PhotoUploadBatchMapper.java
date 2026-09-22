package cn.photolib.photo.batch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PhotoUploadBatchMapper extends BaseMapper<PhotoUploadBatchEntity> {
    @Update("""
            UPDATE photo_upload_batch SET status = #{next}, updated_at = #{now}
            WHERE id = #{id} AND status = #{expected}
            """)
    int transition(@Param("id") String id, @Param("expected") BatchStatus expected,
                   @Param("next") BatchStatus next, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE photo_upload_batch SET archive_object_key = NULL, updated_at = #{now}
            WHERE id = #{id} AND archive_object_key = #{objectKey}
            """)
    int clearArchiveObjectKey(@Param("id") String id, @Param("objectKey") String objectKey,
                              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE photo_upload_batch
            SET status = 'WAITING_METADATA', total_count = #{totalCount},
                failure_reason = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int finishExtraction(@Param("id") String id, @Param("totalCount") int totalCount,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE photo_upload_batch
            SET status = 'FAILED', failure_reason = #{failureReason}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int failExtraction(@Param("id") String id, @Param("failureReason") String failureReason,
                       @Param("now") LocalDateTime now);

    /**
     * 按条目的当前状态重算批次计数和状态。
     *
     * <p>统计和写入放在同一条语句里：整理元数据的请求事务和后台压缩线程会同时收尾
     * 同一个批次，先数再 {@code updateById} 的话，后提交的一方会拿过期的计数把先
     * 提交的覆盖掉；重复项没有后续压缩事件来纠正，批次就一直停在 {@code PROCESSING}。
     * 这条语句写批次行时持有行锁，后到的一方等锁释放后按最新的条目状态重算。</p>
     */
    @Update("""
            UPDATE photo_upload_batch b
            SET success_count = (SELECT COUNT(*) FROM photo_upload_item i
                                 WHERE i.batch_id = b.id AND i.status = 'SUCCEEDED'),
                failure_count = (SELECT COUNT(*) FROM photo_upload_item i
                                 WHERE i.batch_id = b.id AND i.status = 'FAILED'),
                status = CASE
                    WHEN EXISTS (SELECT 1 FROM photo_upload_item i
                                 WHERE i.batch_id = b.id AND i.status = 'WAITING_METADATA')
                        THEN 'WAITING_METADATA'
                    WHEN EXISTS (SELECT 1 FROM photo_upload_item i
                                 WHERE i.batch_id = b.id AND i.status IN ('PROCESSING', 'UPLOADING'))
                        THEN 'PROCESSING'
                    WHEN EXISTS (SELECT 1 FROM photo_upload_item i
                                 WHERE i.batch_id = b.id AND i.status = 'FAILED')
                        THEN 'PARTIALLY_SUCCEEDED'
                    ELSE 'SUCCEEDED'
                END,
                updated_at = #{now}
            WHERE b.id = #{id}
            """)
    int refreshCounters(@Param("id") String id, @Param("now") LocalDateTime now);
}
