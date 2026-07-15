package cn.photolib.content;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.model.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DescriptionImageAuthorizationService {
    private final JdbcClient jdbc;

    public void requireReadable(DescriptionImageEntity image, AuthenticatedUser user) {
        if (user.role() == UserRole.ADMIN || user.role() == UserRole.MINISTER
                || image.getUploadedBy().equals(user.id())) {
            return;
        }
        if (user.role() == UserRole.CAMPUS_MANAGER && user.campusId() != null
                && referencedByVisibleContent(image.getId(), user)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取该说明图片");
    }

    private boolean referencedByVisibleContent(String imageId, AuthenticatedUser user) {
        String needle = "%/api/v1/description-images/" + imageId + "%";
        long requestReferences = jdbc.sql("""
                SELECT COUNT(*) FROM photo_request r
                WHERE r.deleted=FALSE AND r.description LIKE :needle
                  AND r.campus_id=:campusId AND r.status <> 'DRAFT'
                  AND EXISTS (SELECT 1 FROM request_participant rp
                              WHERE rp.request_id=r.id AND rp.user_id=:userId)
                """).param("needle", needle).param("campusId", user.campusId())
                .param("userId", user.id()).query(Long.class).single();
        if (requestReferences > 0) return true;
        long projectReferences = jdbc.sql("""
                SELECT COUNT(*) FROM project p
                WHERE p.deleted=FALSE AND p.description LIKE :needle
                  AND EXISTS (SELECT 1 FROM photo_request r
                              JOIN request_participant rp ON rp.request_id=r.id
                              WHERE r.project_id=p.id AND r.deleted=FALSE AND rp.user_id=:userId)
                """).param("needle", needle).param("userId", user.id())
                .query(Long.class).single();
        return projectReferences > 0;
    }
}
