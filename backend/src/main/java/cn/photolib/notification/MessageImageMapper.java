package cn.photolib.notification;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface MessageImageMapper extends BaseMapper<MessageImageEntity> {

    @Select("""
            SELECT COUNT(*) FROM message_image
            WHERE uploaded_by = #{userId} AND created_at >= #{since}
            """)
    long countUploadedSince(@Param("userId") long userId, @Param("since") LocalDateTime since);
}
