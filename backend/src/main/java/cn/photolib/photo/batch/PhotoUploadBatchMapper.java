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
}
