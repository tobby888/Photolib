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
            WHERE id = #{id}
            """)
    int clearArchiveObjectKey(@Param("id") String id, @Param("now") LocalDateTime now);
}
