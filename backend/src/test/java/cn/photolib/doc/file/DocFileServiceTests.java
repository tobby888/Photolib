package cn.photolib.doc.file;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.doc.DocAudience;
import cn.photolib.doc.DocReader;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.permission.PermissionGroupService;
import cn.photolib.permission.PhotoVisibility;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件库的业务规则：上传要权限、下载范围由上传者指定、所有额度来自管理员的上传限额，
 * 以及"列表不得列出下载不了的文件"这条不变量。
 */
@SpringBootTest
@Transactional
class DocFileServiceTests {
    private static final long UPLOADER_ID = 9_961L;
    private static final long OTHER_UPLOADER_ID = 9_962L;
    private static final long LISTED_ID = 9_963L;
    private static final long OUTSIDER_ID = 9_964L;
    private static final long MANAGER_ID = 9_965L;
    private static final long GROUP_MEMBER_ID = 9_966L;

    @Autowired private DocFileService service;
    @Autowired private DocFileMapper mapper;
    @Autowired private ObjectStorageService storage;
    @Autowired private UploadLimitService limits;
    @Autowired private JdbcClient jdbc;
    @Autowired private PermissionGroupService permissionGroups;
    @Autowired private Clock clock;

    private AuthenticatedUser uploader;
    private AuthenticatedUser otherUploader;
    private AuthenticatedUser manager;
    private long groupId;

    @BeforeEach
    void setUp() {
        for (long id : List.of(UPLOADER_ID, OTHER_UPLOADER_ID, LISTED_ID, OUTSIDER_ID, MANAGER_ID, GROUP_MEMBER_ID)) {
            jdbc.sql("""
                    INSERT INTO app_user
                        (id, username, password_hash, display_name, role, enabled, must_change_password)
                    VALUES (:id, :username, 'hash', :username, 'CAMPUS_MANAGER', TRUE, FALSE)
                    """).param("id", id).param("username", "doc-file-" + id).update();
        }
        groupId = jdbc.sql("SELECT id FROM permission_group WHERE code='CAMPUS_MANAGER'")
                .query(Long.class).single();
        uploader = member(UPLOADER_ID, Set.of(PermissionCode.FILE_UPLOAD));
        otherUploader = member(OTHER_UPLOADER_ID, Set.of(PermissionCode.FILE_UPLOAD));
        manager = member(MANAGER_ID, Set.of(PermissionCode.FILE_MANAGE));
    }

    @Test
    void uploadingNeedsItsOwnPermission() {
        AuthenticatedUser reader = member(OUTSIDER_ID, Set.of(PermissionCode.DOC_MANAGE, PermissionCode.PHOTO_VIEW));
        assertThatThrownBy(() -> upload(reader, "无权上传.txt", "x", DocVisibility.PUBLIC, Set.of(), Set.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void filesAreStoredAsOpaqueBytesAndDefaultToLoggedInReaders() throws IOException {
        DocFileService.FileView file = service.upload(
                new MockMultipartFile("file", "C:\\fakepath\\攻略.html", "text/html",
                        "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null, null, uploader);

        // 默认"登录后"：忘了设置的后果应该是少给人看。
        assertThat(file.visibility()).isEqualTo(DocVisibility.MEMBERS);
        // 路径去掉，只留文件名；标题默认取文件名去掉扩展名。
        assertThat(file.fileName()).isEqualTo("攻略.html");
        assertThat(file.title()).isEqualTo("攻略");
        assertThat(file.contentType()).isEqualTo("text/html");
        // 存进对象存储的类型一律是二进制：本地存储的签名地址与站点同源，原样回吐 text/html 就是 XSS。
        DocFileEntity entity = mapper.findByPublicId(file.publicId());
        assertThat(storage.stat(entity.getObjectKey()).contentType()).isEqualTo("application/octet-stream");
        try (InputStream input = storage.open(entity.getObjectKey())) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).contains("alert");
        }
    }

    /**
     * 列表条件写在 SQL 里、下载判定写在 Java 里，两边必须是同一条规则。
     * 这里把各档读者对各档文件挨个对一遍：列出来的恰好就是下载得了的。
     */
    @Test
    void listingNeverShowsAFileTheReaderCannotDownload() throws IOException {
        upload(uploader, "公开.txt", "a", DocVisibility.PUBLIC, Set.of(), Set.of());
        upload(uploader, "登录后.txt", "b", DocVisibility.MEMBERS, Set.of(), Set.of());
        upload(uploader, "按组.txt", "c", DocVisibility.RESTRICTED, Set.of(groupId), Set.of());
        upload(uploader, "按人.txt", "d", DocVisibility.RESTRICTED, Set.of(), Set.of(LISTED_ID));
        upload(otherUploader, "别人的按人.txt", "e", DocVisibility.RESTRICTED, Set.of(), Set.of(LISTED_ID));

        Map<String, DocReader> readers = Map.of(
                "匿名", DocReader.ANONYMOUS,
                "名单外成员", new DocReader(OUTSIDER_ID, null, Set.of()),
                "组内成员", new DocReader(GROUP_MEMBER_ID, groupId, Set.of()),
                "名单上的人", new DocReader(LISTED_ID, null, Set.of()),
                "上传者", DocReader.of(uploader),
                "管理员", DocReader.of(manager));
        Map<String, Set<String>> expected = Map.of(
                "匿名", Set.of("公开"),
                "名单外成员", Set.of("公开", "登录后"),
                "组内成员", Set.of("公开", "登录后", "按组"),
                "名单上的人", Set.of("公开", "登录后", "按人", "别人的按人"),
                "上传者", Set.of("公开", "登录后", "按组", "按人"),
                "管理员", Set.of("公开", "登录后", "按组", "按人", "别人的按人"));

        readers.forEach((name, reader) -> {
            List<DocFileService.FileView> listed = service.list(reader, null, false, 1, 100).items();
            assertThat(titles(listed)).as(name).isEqualTo(expected.get(name));
            Set<String> downloadable = new HashSet<>();
            for (DocFileEntity file : mapper.selectList(null)) {
                try {
                    service.download(file.getPublicId(), reader, "10.0.0.1");
                    downloadable.add(file.getTitle());
                } catch (BusinessException denied) {
                    assertThat(denied.getCode()).as(name).isEqualTo(ErrorCode.FORBIDDEN);
                }
            }
            assertThat(downloadable).as(name).isEqualTo(expected.get(name));
        });
    }

    @Test
    void downloadsAreSignedLinksWhoseLifetimeTheAdministratorSets() throws IOException {
        limits.update(Map.of(UploadLimit.FILE_DOWNLOAD_LINK_TTL_SECONDS.name(), 60L), null);
        DocFileService.FileView file = upload(uploader, "说明书.pdf", "%PDF-1.4", DocVisibility.MEMBERS, Set.of(), Set.of());

        DocFileService.Download download = service.download(file.publicId(), new DocReader(OUTSIDER_ID, null, Set.of()),
                "10.0.0.1");
        assertThat(download.downloadUrl()).startsWith("http://localhost:8080/api/v1/local-storage/objects/");
        assertThat(download.fileName()).isEqualTo("说明书.pdf");
        assertThat(download.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(61));
        assertThat(mapper.findByPublicId(file.publicId()).getDownloadCount()).isEqualTo(1);
        // 未登录的人下载"登录后"的文件：明确叫他去登录。
        assertThatThrownBy(() -> service.download(file.publicId(), DocReader.ANONYMOUS, "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录");
    }

    @Test
    void anonymousTrafficIsCappedPerDayWhileMembersKeepDownloading() throws IOException {
        limits.update(Map.of(UploadLimit.FILE_ANONYMOUS_DAILY_BYTES.name(), 1024L * 1024), null);
        byte[] content = new byte[600 * 1024];
        DocFileService.FileView file = service.upload(new MockMultipartFile("file", "大图.zip", "application/zip", content),
                null, null, DocVisibility.PUBLIC, Set.of(), Set.of(), uploader);

        service.download(file.publicId(), DocReader.ANONYMOUS, "10.0.0.1");
        // 600 KiB + 600 KiB > 1 MiB：第二次匿名下载被拒，和地址无关（换个地址也一样）。
        assertThatThrownBy(() -> service.download(file.publicId(), DocReader.ANONYMOUS, "10.0.0.2"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RATE_LIMITED);
        // 成员可追责，不受这条全站匿名额度约束。
        service.download(file.publicId(), new DocReader(OUTSIDER_ID, null, Set.of()), "10.0.0.1");

        long anonymousBytes = jdbc.sql("""
                SELECT bytes FROM doc_file_download_usage WHERE reader_scope='ANONYMOUS'
                """).query(Long.class).single();
        assertThat(anonymousBytes).isEqualTo(600L * 1024);
    }

    @Test
    void eachReaderHasAnHourlyDownloadAllowance() throws IOException {
        limits.update(Map.of(UploadLimit.FILE_DOWNLOADS_PER_HOUR.name(), 1L), null);
        DocFileService.FileView file = upload(uploader, "每小时.txt", "x", DocVisibility.PUBLIC, Set.of(), Set.of());

        // 匿名按公网地址计数。
        service.download(file.publicId(), DocReader.ANONYMOUS, "198.51.100.61");
        assertThatThrownBy(() -> service.download(file.publicId(), DocReader.ANONYMOUS, "198.51.100.61"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RATE_LIMITED);
        // 成员按账号计，和地址无关。
        DocReader member = new DocReader(OUTSIDER_ID, null, Set.of());
        service.download(file.publicId(), member, "198.51.100.62");
        assertThatThrownBy(() -> service.download(file.publicId(), member, "198.51.100.63"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void sizeQuotaAndDailyCountAllComeFromTheUploadLimits() throws IOException {
        limits.update(Map.of(UploadLimit.FILE_MAX_BYTES.name(), 1024L * 1024), null);
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "太大.bin", null,
                        new byte[1024 * 1024 + 1]), null, null, DocVisibility.MEMBERS, null, null, uploader))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FILE_TOO_LARGE);

        // 存储空间：已有的文件占满了额度，再小的文件也传不上。
        limits.update(Map.of(UploadLimit.FILE_USER_QUOTA_BYTES.name(), 10L * 1024 * 1024), null);
        // 建于"昨天"（按业务时钟算，不能用库的 CURRENT_TIMESTAMP：CI 跑在 UTC 上），只占空间、不占今天的个数。
        jdbc.sql("""
                INSERT INTO doc_file(public_id, title, file_name, content_type, size, object_key,
                                     visibility, uploaded_by, created_at, updated_at)
                VALUES ('01HZZZZZZZZZZZZZZZZZZZZZZZ', '占位', '占位.bin', 'application/octet-stream',
                        :size, 'doc-files/占位', 'MEMBERS', :uploader, :yesterday, :yesterday)
                """).param("size", 10L * 1024 * 1024).param("uploader", UPLOADER_ID)
                .param("yesterday", LocalDateTime.now(clock).minusDays(1)).update();
        assertThatThrownBy(() -> upload(uploader, "再一个.txt", "x", DocVisibility.MEMBERS, Set.of(), Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存储空间");
        // 删掉之后空间就回来了。
        long placeholder = mapper.findByPublicId("01HZZZZZZZZZZZZZZZZZZZZZZZ").getId();
        service.delete(placeholder, 1, uploader);
        assertThat(service.usage(uploader).usedBytes()).isZero();

        // 每日个数：删掉的也算，否则"传了删、删了传"就绕过去了。
        limits.update(Map.of(UploadLimit.FILE_USER_DAILY_UPLOADS.name(), 1L), null);
        DocFileService.FileView first = upload(uploader, "今天第一个.txt", "x", DocVisibility.MEMBERS, Set.of(), Set.of());
        service.delete(first.id(), first.version(), uploader);
        assertThatThrownBy(() -> upload(uploader, "今天第二个.txt", "x", DocVisibility.MEMBERS, Set.of(), Set.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RATE_LIMITED);
        // 别人的额度互不影响。
        upload(otherUploader, "别人的.txt", "x", DocVisibility.MEMBERS, Set.of(), Set.of());
    }

    @Test
    void onlyTheUploaderOrAFileManagerChangesWhoCanDownload() throws IOException {
        DocFileService.FileView file = upload(uploader, "名单.txt", "x", DocVisibility.MEMBERS, Set.of(), Set.of());

        assertThatThrownBy(() -> service.update(file.id(), "改个名", null, DocVisibility.PUBLIC, null, null,
                file.version(), otherUploader))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN);

        DocFileService.FileView restricted = service.update(file.id(), "名单", "只给一个人", DocVisibility.RESTRICTED,
                Set.of(), Set.of(LISTED_ID), file.version(), uploader);
        assertThat(restricted.readerUserIds()).containsExactly(LISTED_ID);
        // 名单只给能管理的人看。
        assertThat(service.list(new DocReader(LISTED_ID, null, Set.of()), null, false, 1, 20).items())
                .singleElement().satisfies(view -> {
                    assertThat(view.canManage()).isFalse();
                    assertThat(view.readerUserIds()).isEmpty();
                });

        DocFileService.FileView widened = service.update(file.id(), "名单", null, DocVisibility.PUBLIC,
                Set.of(), Set.of(LISTED_ID), restricted.version(), manager);
        assertThat(widened.readerUserIds()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM resource_access_grant WHERE resource_type='FILE' AND resource_id=:id")
                .param("id", file.id()).query(Long.class).single()).isZero();

        // 删除是软删：列表里消失、下载不到，对象存储里的文件还在。
        String objectKey = mapper.findByPublicId(file.publicId()).getObjectKey();
        service.delete(file.id(), widened.version(), manager);
        assertThat(service.list(DocReader.ANONYMOUS, null, false, 1, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.download(file.publicId(), DocReader.of(manager), "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(storage.find(objectKey)).isPresent();
    }

    @Test
    void deletingAPermissionGroupDropsTheGrantsThatPointAtIt() throws IOException {
        long customGroup = permissionGroups.create(new PermissionGroupService.CreateCommand(
                "DOC_FILE_TEMP", "临时组", null, DataScope.GLOBAL, PhotoVisibility.GLOBAL, Set.of())).id();
        DocFileService.FileView file = upload(uploader, "按临时组.txt", "x", DocVisibility.RESTRICTED,
                Set.of(customGroup), Set.of());
        DocReader inGroup = new DocReader(GROUP_MEMBER_ID, customGroup, Set.of());
        assertThat(service.list(inGroup, null, false, 1, 20).items()).hasSize(1);

        permissionGroups.delete(customGroup);
        // 组没了，按组授予的下载范围随之收窄，而不是悄悄落到别的组上。
        assertThat(service.list(inGroup, null, false, 1, 20).items()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM resource_access_grant WHERE grantee_type='GROUP' AND grantee_id=:id")
                .param("id", customGroup).query(Long.class).single()).isZero();
        assertThat(file.visibility()).isEqualTo(DocVisibility.RESTRICTED);
    }

    @Test
    void theAudienceCheckIsTheSameRuleForEveryReader() {
        DocAudience.Grants grants = new DocAudience.Grants(Set.of(7L), Set.of(42L));
        assertThat(DocAudience.allows(DocVisibility.PUBLIC, grants, DocReader.ANONYMOUS)).isTrue();
        assertThat(DocAudience.allows(DocVisibility.MEMBERS, grants, DocReader.ANONYMOUS)).isFalse();
        assertThat(DocAudience.allows(DocVisibility.RESTRICTED, grants, DocReader.ANONYMOUS)).isFalse();
        assertThat(DocAudience.allows(DocVisibility.RESTRICTED, grants, new DocReader(1L, 7L, Set.of()))).isTrue();
        assertThat(DocAudience.allows(DocVisibility.RESTRICTED, grants, new DocReader(42L, 8L, Set.of()))).isTrue();
        assertThat(DocAudience.allows(DocVisibility.RESTRICTED, grants, new DocReader(1L, 8L, Set.of()))).isFalse();
        // 脏数据（可见范围缺失）按"登录后"处理，宁可少给人看。
        assertThat(DocAudience.allows(null, grants, DocReader.ANONYMOUS)).isFalse();
    }

    private DocFileService.FileView upload(AuthenticatedUser user, String name, String content,
                                           DocVisibility visibility, Set<Long> groups, Set<Long> users)
            throws IOException {
        return service.upload(new MockMultipartFile("file", name, "text/plain",
                        content.getBytes(StandardCharsets.UTF_8)),
                null, null, visibility, groups, users, user);
    }

    private static Set<String> titles(List<DocFileService.FileView> files) {
        Set<String> titles = new HashSet<>();
        files.forEach(file -> titles.add(file.title()));
        return titles;
    }

    private AuthenticatedUser member(long id, Set<PermissionCode> permissions) {
        return new AuthenticatedUser(id, "doc-file-" + id, "doc-file-" + id, UserRole.CAMPUS_MANAGER, null, false,
                groupId, "CAMPUS_MANAGER", "校区负责人", DataScope.GLOBAL, permissions, Set.of());
    }
}
