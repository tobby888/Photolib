package cn.photolib.notification;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.LikeFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Decides who may read a message image.
 *
 * <p>Mirrors {@code DescriptionImageAuthorizationService}: the annotation on the
 * controller only proves the caller is signed in, which for a private message
 * attachment is not the same question. Ids are unguessable, but that is
 * obscurity, not authorization — and a link pasted out of one inbox should not
 * open for the whole department.
 */
@Service
@RequiredArgsConstructor
public class MessageImageAuthorizationService {
    private final JdbcClient jdbc;

    public void requireReadable(MessageImageEntity image, AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取该消息图片");
        }
        // The sender keeps access to what they uploaded, including while they are
        // still composing and the message has no recipients yet.
        if (user.id().equals(image.getUploadedBy()) || user.isAdministrator()) {
            return;
        }
        if (deliveredTo(image.getId(), user.id())) {
            return;
        }
        if (referencedInFeedback(image.getId(), user.id())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取该消息图片");
    }

    /** True when a notification carrying this image was delivered to the caller. */
    private boolean deliveredTo(String imageId, Long userId) {
        String needle = "%" + LikeFilter.escape("/api/v1/notifications/images/" + imageId) + "%";
        return jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id = :userId
                  AND content_html LIKE :needle ESCAPE '!'
                """).param("userId", userId).param("needle", needle)
                .query(Long.class).single() > 0;
    }

    /**
     * True when someone other than the caller replied with this image in a feedback the
     * caller submitted.
     *
     * <p>反馈线程的回复存在 {@code feedback_reply}，不在 {@code user_notification} 里，所以提交人
     * 要读管理员回复里贴的图得单独认这一条。只认<b>别人</b>写的回复：正文和提交人自己的追加
     * 由提交人随意填写，消毒只校验图片地址的格式、不校验是谁传的，若也算数，任何人把别处
     * 拿到的图片链接贴进自己的反馈就能读到它。提交人自己上传的图已由「上传人」那条放行。</p>
     */
    private boolean referencedInFeedback(String imageId, Long userId) {
        String needle = "%" + LikeFilter.escape("/api/v1/notifications/images/" + imageId) + "%";
        return jdbc.sql("""
                SELECT COUNT(*) FROM feedback f
                JOIN feedback_reply r ON r.feedback_id = f.id
                WHERE f.submitter_id = :userId
                  AND r.author_id <> f.submitter_id
                  AND r.content_html LIKE :needle ESCAPE '!'
                """).param("userId", userId).param("needle", needle)
                .query(Long.class).single() > 0;
    }
}
