package cn.photolib.content;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DescriptionImageAuthorizationService {
    private final JdbcClient jdbc;

    public void requireReadable(DescriptionImageEntity image, AuthenticatedUser user) {
        if (!user.isCampusScoped()
                || image.getUploadedBy().equals(user.id())) {
            return;
        }
        if (referencedByVisibleContent(image.getId(), user)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取该说明图片");
    }

    private boolean referencedByVisibleContent(String imageId, AuthenticatedUser user) {
        String needle = "%/api/v1/description-images/" + imageId + "%";
        long requestReferences = jdbc.sql("""
                SELECT COUNT(*) FROM photo_request r
                WHERE r.deleted=FALSE AND r.description LIKE :needle
                  AND r.status <> 'DRAFT'
                  AND EXISTS (SELECT 1 FROM user_campus_permission ucp
                              WHERE ucp.user_id=:userId AND ucp.campus_id=r.campus_id)
                  AND EXISTS (SELECT 1 FROM request_participant rp
                              WHERE rp.request_id=r.id AND rp.user_id=:userId)
                """).param("needle", needle).param("userId", user.id()).query(Long.class).single();
        if (requestReferences > 0) return true;
        long projectReferences = jdbc.sql("""
                SELECT COUNT(*) FROM project p
                WHERE p.deleted=FALSE AND p.description LIKE :needle
                  AND EXISTS (SELECT 1 FROM photo_request r
                              JOIN request_participant rp ON rp.request_id=r.id
                              WHERE r.project_id=p.id AND r.deleted=FALSE AND rp.user_id=:userId)
                """).param("needle", needle).param("userId", user.id())
                .query(Long.class).single();
        if (projectReferences > 0) return true;
        // 好图精选的查看不设限，所以只要图片被一份已发布（或已截止）的精选引用，
        // 任何登录用户都能读到它——否则校区负责人会看到一份缺图的征集要求。
        // 草稿仍然只有上传者本人可见，这一点与项目/需求说明一致。
        return jdbc.sql("""
                SELECT COUNT(*) FROM featured_collection c
                WHERE c.deleted=FALSE AND c.status <> 'DRAFT'
                  AND c.requirement_html LIKE :needle
                """).param("needle", needle).query(Long.class).single() > 0;
    }
}
