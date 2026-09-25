package cn.photolib.feedback.mapper;

import cn.photolib.feedback.model.FeedbackReplyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FeedbackReplyMapper extends BaseMapper<FeedbackReplyEntity> {

    @Select("""
            SELECT r.*, u.display_name AS author_display_name
            FROM feedback_reply r
            JOIN app_user u ON u.id = r.author_id
            WHERE r.feedback_id = #{feedbackId}
            ORDER BY r.created_at ASC, r.id ASC
            """)
    List<FeedbackReplyEntity> findByFeedbackId(@Param("feedbackId") long feedbackId);
}
