package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 选片页「裁切 / 旋转」保存后的就地替换（issue #94）。
 *
 * <p>两条不变量：成功时**每一样都换新**（对象 key、哈希、尺寸、预览），失败时
 * **照片一个字节都不动**。中间态——数据库指向一个还没传上去、或者根本传不上去的
 * 对象——会在相册里留下一张打不开的图，而且没有任何东西会去修它。</p>
 */
@SpringBootTest
class PhotoImageEditTests {
    private static final long USER_ID = 94001L;
    private static final long PHOTO_ID = 94002L;
    private static final String ORIGINAL_PHOTO_KEY = "photos/2026/edit-source.jpg";
    private static final String ORIGINAL_UPLOAD_KEY = "temporary/photos/edit-source.jpg";
    private static final String ORIGINAL_PREVIEW_KEY = "thumbnails/generations/uploads/94002.webp";

    @Autowired
    private PhotoImageEditService edits;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser admin;
    private byte[] editedBytes;
    private String editedSha;
    private String editSourceKey;

    @BeforeEach
    void setUp() throws Exception {
        admin = new AuthenticatedUser(USER_ID, "edit-admin", "编辑管理员", UserRole.ADMIN, null, false);
        byte[] published = jpeg(1200, 800);
        put(ORIGINAL_PHOTO_KEY, published);
        put(ORIGINAL_UPLOAD_KEY, published);
        put(ORIGINAL_PREVIEW_KEY, published);

        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password,
                     version, deleted)
                VALUES (:id, 'edit-admin', 'hash', '编辑管理员', 'ADMIN', true, false, 1, false)
                """).param("id", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by, taken_at,
                     tags_json, width, height, size, content_type, object_key, thumbnail_object_key,
                     thumbnail_size, original_object_key, stored_file_name, sha256, status, version, deleted)
                VALUES (:id, '待编辑', '20294001', '拍摄者', :userId, :takenAt, '["合影"]', 1200, 800,
                        :size, 'image/jpeg', :objectKey, :previewKey, :size, :uploadKey, 'photo.jpg',
                        :sha, 'AVAILABLE', 1, false)
                """)
                .param("id", PHOTO_ID)
                .param("userId", USER_ID)
                .param("takenAt", LocalDateTime.now().minusHours(1))
                .param("size", (long) published.length)
                .param("objectKey", ORIGINAL_PHOTO_KEY)
                .param("previewKey", ORIGINAL_PREVIEW_KEY)
                .param("uploadKey", ORIGINAL_UPLOAD_KEY)
                .param("sha", sha256(published))
                .update();

        // 浏览器裁出来的那一半：换了尺寸，所以字节和哈希必然与原图不同。
        editedBytes = jpeg(600, 800);
        editedSha = sha256(editedBytes);
        editSourceKey = edits.ticket(PHOTO_ID, "image/jpeg", editedBytes.length, admin).sourceObjectKey();
        put(editSourceKey, editedBytes);
    }

    @AfterEach
    void tearDown() {
        // 成功用例已经把旧对象删了，失败用例没有；两种情况都按 key 逐个清，
        // 删不掉就算了——这里清的是测试自己造的垃圾。
        for (String key : new String[]{ORIGINAL_PHOTO_KEY, ORIGINAL_UPLOAD_KEY, ORIGINAL_PREVIEW_KEY,
                editSourceKey, currentObjectKey(), currentPreviewKey()}) {
            if (key != null) {
                try {
                    storage.delete(key);
                } catch (RuntimeException ignored) {
                    // 对象可能已经被业务逻辑删掉了。
                }
            }
        }
        jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
        jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
    }

    @Test
    void replacesEveryDerivedFieldAndDropsTheSupersededObjects() {
        var view = edits.apply(PHOTO_ID, new PhotoImageEditService.ApplyEdit(
                editSourceKey, "image/jpeg", editedBytes.length, editedSha), admin);

        assertThat(view.status().name()).isEqualTo("AVAILABLE");
        // 裁成竖构图之后尺寸必须跟着变，否则前端会按旧的宽高去排版。
        assertThat(view.width()).isEqualTo(600);
        assertThat(view.height()).isEqualTo(800);
        assertThat(view.version()).isEqualTo(2);
        // 标签、标题这些与画面无关的东西不受影响。
        assertThat(view.tags()).containsExactly("合影");
        assertThat(view.title()).isEqualTo("待编辑");

        // 对象 key 必须是全新的：同一个 key 在签名窗口内签出的地址逐字节相同，
        // 复用就等于让浏览器继续拿缓存里的旧画面。
        String objectKey = currentObjectKey();
        assertThat(objectKey).isNotEqualTo(ORIGINAL_PHOTO_KEY).startsWith("photos/");
        assertThat(storage.find(objectKey)).isPresent();
        assertThat(storage.find(ORIGINAL_PHOTO_KEY)).isEmpty();

        String previewKey = currentPreviewKey();
        assertThat(previewKey).isNotEqualTo(ORIGINAL_PREVIEW_KEY)
                .startsWith("thumbnails/generations/");
        assertThat(storage.find(previewKey)).isPresent();
        assertThat(storage.find(ORIGINAL_PREVIEW_KEY)).isEmpty();

        // 哈希记的是进入压缩之前的字节，与单张上传一致，全库查重才对得上。
        assertThat(currentSha()).isEqualTo(editedSha);
        // 原图指向这次编辑的来源，旧的那份原图已经清掉。
        assertThat(currentOriginalKey()).isEqualTo(editSourceKey);
        assertThat(storage.find(ORIGINAL_UPLOAD_KEY)).isEmpty();
    }

    @Test
    void leavesThePhotoUntouchedWhenTheUploadedBytesDoNotMatchTheDeclaredHash() {
        String bogus = "a".repeat(64);
        assertThatThrownBy(() -> edits.apply(PHOTO_ID, new PhotoImageEditService.ApplyEdit(
                editSourceKey, "image/jpeg", editedBytes.length, bogus), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SHA-256");

        assertUnchanged();
    }

    @Test
    void refusesASourceKeyItDidNotIssue() {
        assertThatThrownBy(() -> edits.apply(PHOTO_ID, new PhotoImageEditService.ApplyEdit(
                ORIGINAL_PHOTO_KEY, "image/jpeg", editedBytes.length, editedSha), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("编辑来源地址无效");
        // 路径穿越同样只是一条不合形状的 key，走同一个出口。
        assertThatThrownBy(() -> edits.apply(PHOTO_ID, new PhotoImageEditService.ApplyEdit(
                "temporary/photo-edits/../../photos/2026/other.jpg", "image/jpeg",
                editedBytes.length, editedSha), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("编辑来源地址无效");

        assertUnchanged();
    }

    @Test
    void refusesAContentTypeThatContradictsTheIssuedSourceKey() {
        // 票据签的是 .jpg，声明成 PNG 会让魔数校验和扩展名各说各话。
        assertThatThrownBy(() -> edits.apply(PHOTO_ID, new PhotoImageEditService.ApplyEdit(
                editSourceKey, "image/png", editedBytes.length, editedSha), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("与声明的图片类型不一致");

        assertUnchanged();
    }

    @Test
    void refusesToEditAPhotoThatIsNotAvailable() {
        jdbc.sql("UPDATE photo SET status='PROCESSING' WHERE id=:id").param("id", PHOTO_ID).update();
        assertThatThrownBy(() -> edits.ticket(PHOTO_ID, "image/jpeg", editedBytes.length, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有可用状态");
    }

    /** 失败之后照片必须和编辑前逐列相同，桶里的三个旧对象也都还在。 */
    private void assertUnchanged() {
        assertThat(currentObjectKey()).isEqualTo(ORIGINAL_PHOTO_KEY);
        assertThat(currentPreviewKey()).isEqualTo(ORIGINAL_PREVIEW_KEY);
        assertThat(currentOriginalKey()).isEqualTo(ORIGINAL_UPLOAD_KEY);
        assertThat(currentVersion()).isEqualTo(1);
        assertThat(currentSha()).isNotEqualTo(editedSha);
        assertThat(storage.find(ORIGINAL_PHOTO_KEY)).isPresent();
        assertThat(storage.find(ORIGINAL_PREVIEW_KEY)).isPresent();
        assertThat(storage.find(ORIGINAL_UPLOAD_KEY)).isPresent();
    }

    private String currentObjectKey() {
        return column("object_key");
    }

    private String currentPreviewKey() {
        return column("thumbnail_object_key");
    }

    private String currentOriginalKey() {
        return column("original_object_key");
    }

    private String currentSha() {
        return column("sha256");
    }

    private int currentVersion() {
        return jdbc.sql("SELECT version FROM photo WHERE id=:id").param("id", PHOTO_ID)
                .query(Integer.class).optional().orElse(-1);
    }

    private String column(String name) {
        return jdbc.sql("SELECT " + name + " FROM photo WHERE id=:id").param("id", PHOTO_ID)
                .query(String.class).optional().orElse(null);
    }

    private void put(String objectKey, byte[] bytes) {
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
    }

    private byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color((x * 13 + y) & 255, (x + y * 7) & 255, (x * y) & 255).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings("unused")
    private long sizeOf(String objectKey) throws Exception {
        try (InputStream input = storage.open(objectKey)) {
            return input.readAllBytes().length;
        }
    }
}
