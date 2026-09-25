package cn.photolib.notification;

import cn.photolib.common.util.PublicId;
import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OrphanMessageImageCleanupJobTests {
    private static final long UPLOADER_ID = 841L;

    @Autowired
    private OrphanMessageImageCleanupJob job;
    @Autowired
    private MessageImageMapper mapper;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (841, 'orphan-image-uploader', 'hash', '上传人', 'CAMPUS_MANAGER', true, false)
                """).update();
    }

    @Test
    void removesOnlyOldImagesThatNothingReferences() {
        String orphan = insertImage(LocalDateTime.now().minusDays(10));
        String inMessage = insertImage(LocalDateTime.now().minusDays(10));
        String inFeedback = insertImage(LocalDateTime.now().minusDays(10));
        String inReply = insertImage(LocalDateTime.now().minusDays(10));
        String fresh = insertImage(LocalDateTime.now().minusHours(1));
        // Past the scan window: already had a month of nightly chances.
        String beyondWindow = insertImage(LocalDateTime.now().minusDays(100));

        jdbc.sql("""
                INSERT INTO user_notification
                    (user_id, event_type, title, content, content_html, sender_id, created_at)
                VALUES (841, 'DIRECT_MESSAGE', '通知', '正文', :html, 841, CURRENT_TIMESTAMP)
                """).param("html", img(inMessage)).update();
        jdbc.sql("""
                INSERT INTO feedback (id, submitter_id, title, content, content_html, category, status)
                VALUES (9500001, 841, '反馈', '正文', :html, 'ISSUE', 'PENDING')
                """).param("html", img(inFeedback)).update();
        jdbc.sql("""
                INSERT INTO feedback_reply (feedback_id, author_id, content, content_html, created_at)
                VALUES (9500001, 841, '回复', :html, CURRENT_TIMESTAMP)
                """).param("html", img(inReply)).update();

        try {
            assertThat(job.cleanup()).isGreaterThanOrEqualTo(1);

            assertThat(mapper.selectById(orphan)).isNull();
            assertThat(storage.find(objectKey(orphan))).isEmpty();
            assertThat(mapper.selectById(inMessage)).isNotNull();
            assertThat(mapper.selectById(inFeedback)).isNotNull();
            assertThat(mapper.selectById(inReply)).isNotNull();
            assertThat(mapper.selectById(fresh)).isNotNull();
            assertThat(mapper.selectById(beyondWindow)).isNotNull();
        } finally {
            for (String id : new String[]{orphan, inMessage, inFeedback, inReply, fresh, beyondWindow}) {
                storage.delete(objectKey(id));
            }
        }
    }

    private String insertImage(LocalDateTime createdAt) {
        String id = PublicId.next();
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        storage.put(objectKey(id), new ByteArrayInputStream(png), png.length, "image/png");
        MessageImageEntity image = new MessageImageEntity();
        image.setId(id);
        image.setObjectKey(objectKey(id));
        image.setContentType("image/png");
        image.setSize((long) png.length);
        image.setUploadedBy(UPLOADER_ID);
        image.setCreatedAt(createdAt);
        mapper.insert(image);
        return id;
    }

    private static String objectKey(String id) {
        return "messages/" + id + ".png";
    }

    private static String img(String id) {
        return "<p>图</p><img src=\"/api/v1/notifications/images/" + id + "\">";
    }
}
