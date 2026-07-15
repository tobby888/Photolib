package cn.photolib.photo.batch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PhotoUploadItemMapper extends BaseMapper<PhotoUploadItemEntity> {
    @Update("""
            UPDATE photo_upload_item SET status = #{next}, updated_at = #{now}
            WHERE id = #{id} AND status = #{expected}
            """)
    int transition(@Param("id") Long id, @Param("expected") BatchItemStatus expected,
                   @Param("next") BatchItemStatus next, @Param("now") LocalDateTime now);
}
