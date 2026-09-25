package cn.photolib.feedback.mapper;

import cn.photolib.feedback.model.FeedbackStatusChangeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FeedbackStatusChangeMapper extends BaseMapper<FeedbackStatusChangeEntity> {

    @Select("""
            SELECT s.*, u.display_name AS operator_display_name
            FROM feedback_status_change s
            JOIN app_user u ON u.id = s.operator_id
            WHERE s.feedback_id = #{feedbackId}
            ORDER BY s.created_at ASC, s.id ASC
            """)
    List<FeedbackStatusChangeEntity> findByFeedbackId(@Param("feedbackId") long feedbackId);
}
