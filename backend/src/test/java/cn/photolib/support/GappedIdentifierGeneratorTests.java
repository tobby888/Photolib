package cn.photolib.support;

import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@Transactional
class GappedIdentifierGeneratorTests {
    @Autowired
    private PhotoMapper photoMapper;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void mapperInsertAfterRawAutoIncrementInsert_shouldNotReuseTheId() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role,
                     enabled, must_change_password, version, deleted)
                VALUES (9901, 'id-gap-uploader', 'hash', '主键间隔', 'ADMIN', true, false, 1, false)
                """).update();

        // 修复前：手插的那行拿到「上一个雪花 ID + 1」，同一毫秒内下一次雪花发号也是它，几乎每轮都撞。
        assertThatCode(() -> {
            for (int i = 0; i < 200; i++) {
                photoMapper.insert(photo("id-gap/before-" + i));
                jdbc.sql("""
                        INSERT INTO photo
                            (photographer_student_id, photographer_name, uploaded_by, taken_at,
                             size, content_type, object_key, sha256, status, version, deleted)
                        VALUES ('id-gap', '主键间隔', 9901, CURRENT_TIMESTAMP,
                                1, 'image/jpeg', :key, :key, 'AVAILABLE', 1, false)
                        """).param("key", "id-gap/raw-" + i).update();
                photoMapper.insert(photo("id-gap/after-" + i));
            }
        }).doesNotThrowAnyException();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE uploaded_by = 9901")
                .query(Long.class).single()).isEqualTo(600);
    }

    private static PhotoEntity photo(String objectKey) {
        PhotoEntity photo = new PhotoEntity();
        photo.setPhotographerStudentId("id-gap");
        photo.setPhotographerName("主键间隔");
        photo.setUploadedBy(9901L);
        photo.setTakenAt(LocalDateTime.now());
        photo.setSize(1L);
        photo.setContentType("image/jpeg");
        photo.setObjectKey(objectKey);
        photo.setSha256(objectKey);
        photo.setStatus(PhotoStatus.AVAILABLE);
        return photo;
    }
}
