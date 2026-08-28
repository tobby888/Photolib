package cn.photolib.featured;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.featured.mapper.FeaturedCollectionMapper;
import cn.photolib.featured.model.FeaturedCloseReason;
import cn.photolib.featured.model.FeaturedCollectionEntity;
import cn.photolib.featured.model.FeaturedCollectionStatus;
import cn.photolib.featured.model.FeaturedDocumentStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 好图精选的业务规则与 Word 文档生成。
 *
 * <p>文档生成在生产里由"关闭事务提交之后"的异步监听器触发；用例本身跑在
 * {@code @Transactional} 里、事务最终回滚，监听器不会触发，所以这里直接调用
 * {@link FeaturedDocumentService#generate}，验证的是同一段生成逻辑。</p>
 */
@SpringBootTest
@Transactional
class FeaturedCollectionServiceTests {
    private static final long MINISTER_ID = 9_801L;
    private static final long EAST_MANAGER_ID = 9_802L;
    private static final long WEST_MANAGER_ID = 9_803L;
    private static final long OUTSIDER_ID = 9_804L;
    private static final long EAST_CAMPUS_ID = 9_811L;
    private static final long WEST_CAMPUS_ID = 9_812L;

    @Autowired private FeaturedCollectionService service;
    @Autowired private FeaturedDocumentService documents;
    @Autowired private FeaturedCollectionMapper mapper;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;
    private AuthenticatedUser eastManager;
    private AuthenticatedUser westManager;
    private AuthenticatedUser outsider;

    @BeforeEach
    void setUp() {
        campus(EAST_CAMPUS_ID, "FT-EAST", "东校区");
        campus(WEST_CAMPUS_ID, "FT-WEST", "西校区");
        user(MINISTER_ID, "featured-minister", "精选部长", "MINISTER");
        user(EAST_MANAGER_ID, "featured-east", "东校区负责人", "CAMPUS_MANAGER");
        user(WEST_MANAGER_ID, "featured-west", "西校区负责人", "CAMPUS_MANAGER");
        user(OUTSIDER_ID, "featured-outsider", "未指派负责人", "CAMPUS_MANAGER");
        campusPermission(EAST_MANAGER_ID, EAST_CAMPUS_ID);
        campusPermission(WEST_MANAGER_ID, WEST_CAMPUS_ID);
        campusPermission(OUTSIDER_ID, WEST_CAMPUS_ID);

        minister = new AuthenticatedUser(MINISTER_ID, "featured-minister", "精选部长",
                UserRole.MINISTER, null, false);
        eastManager = new AuthenticatedUser(EAST_MANAGER_ID, "featured-east", "东校区负责人",
                UserRole.CAMPUS_MANAGER, EAST_CAMPUS_ID, false);
        westManager = new AuthenticatedUser(WEST_MANAGER_ID, "featured-west", "西校区负责人",
                UserRole.CAMPUS_MANAGER, WEST_CAMPUS_ID, false);
        outsider = new AuthenticatedUser(OUTSIDER_ID, "featured-outsider", "未指派负责人",
                UserRole.CAMPUS_MANAGER, WEST_CAMPUS_ID, false);
    }

    // ------------------------------------------------------------------
    // 生命周期与权限
    // ------------------------------------------------------------------

    @Test
    void lifecycleRunsFromDraftThroughPublishToClose() {
        var created = service.create(command("春季好图精选"), minister);
        assertThat(created.status()).isEqualTo(FeaturedCollectionStatus.DRAFT);
        assertThat(created.documentStatus()).isEqualTo(FeaturedDocumentStatus.PENDING);
        assertThat(created.canManage()).isTrue();

        var published = service.publish(created.id(), created.version(), minister);
        assertThat(published.status()).isEqualTo(FeaturedCollectionStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();

        // 重复发布必须被版本/状态拦住。
        assertThatThrownBy(() -> service.publish(created.id(), published.version(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);

        var closed = service.close(created.id(), minister);
        assertThat(closed.status()).isEqualTo(FeaturedCollectionStatus.CLOSED);
        assertThat(closed.closedReason()).isEqualTo(FeaturedCloseReason.MANUAL);
        // 关闭即进入生成中；文档由事务提交后的监听器真正产出。
        assertThat(closed.documentStatus()).isEqualTo(FeaturedDocumentStatus.GENERATING);

        assertThatThrownBy(() -> service.close(created.id(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void publishDeleteAndCloseAreReservedForFeaturedManagers() {
        var created = service.create(command("负责人不可管理"), minister);
        assertThatThrownBy(() -> service.create(command("负责人建的"), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.publish(created.id(), created.version(), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.close(created.id(), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.delete(created.id(), created.version(), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void viewingIsOpenToEveryoneWhileSubmittingIsNot() {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        // 未被指派的负责人依然能看到精选和其中的条目。
        assertThat(service.get(id, outsider).assignedToMe()).isFalse();
        assertThat(service.entries(id, outsider)).isEmpty();

        long photo = photo(OUTSIDER_ID, WEST_CAMPUS_ID, "外部照片");
        assertThatThrownBy(() -> service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(photo, "思路", "地点"), outsider))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void assignmentAcceptsWholeCampusesAndIndividuallyPickedManagers() {
        // 只点名西校区负责人本人，不指派任何校区。
        long individual = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "点名精选", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), false, 10, List.of(), List.of(WEST_MANAGER_ID)));
        assertThat(service.get(individual, westManager).assignedToMe()).isTrue();
        // 同校区的另一位负责人没有被点名，因此不能填报。
        assertThat(service.get(individual, outsider).assignedToMe()).isFalse();

        long byCampus = publishedCollection(assignedCampuses(WEST_CAMPUS_ID));
        assertThat(service.get(byCampus, westManager).assignedToMe()).isTrue();
        assertThat(service.get(byCampus, outsider).assignedToMe()).isTrue();

        long everyone = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "全员精选", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), true, 10, List.of(), List.of()));
        assertThat(service.get(everyone, eastManager).assignedToMe()).isTrue();
        assertThat(service.get(everyone, westManager).assignedToMe()).isTrue();
        // 部长自己不是校区负责人，不在填报范围内。
        assertThat(service.get(everyone, minister).assignedToMe()).isFalse();
    }

    @Test
    void bothDirectionsOfTheAssignmentRuleAgreeWithEachOther() {
        // resolveAssignees（发通知时的扇出：一份精选 → 全部负责人）和视图里的
        // "我是否被指派"（一批精选 → 我在不在内）是同一条规则的两个方向，
        // 两条 SQL 分开写就有各自漂移的风险，这里逐人交叉核对。
        List<Long> collections = List.of(
                publishedCollection(assignedCampuses(EAST_CAMPUS_ID)),
                publishedCollection(new FeaturedCollectionService.CollectionCommand(
                        "点名西校区", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                        LocalDateTime.now().plusDays(1), false, 10, List.of(), List.of(WEST_MANAGER_ID))),
                publishedCollection(new FeaturedCollectionService.CollectionCommand(
                        "全员", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                        LocalDateTime.now().plusDays(1), true, 10, List.of(), List.of())));

        for (Long id : collections) {
            Set<Long> fanOut = service.resolveAssignees(mapper.selectById(id));
            for (AuthenticatedUser candidate : List.of(minister, eastManager, westManager, outsider)) {
                assertThat(service.get(id, candidate).assignedToMe())
                        .as("collection %s / user %s", id, candidate.id())
                        .isEqualTo(fanOut.contains(candidate.id()));
            }
        }
    }

    // ------------------------------------------------------------------
    // 填报规则
    // ------------------------------------------------------------------

    @Test
    void submissionSnapshotsPhotographerAndTakenAtFromTheGallery() {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        long photoId = photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "日出");

        var entry = service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(photoId, "逆光剪影", "图书馆天台"), eastManager);

        assertThat(entry.idea()).isEqualTo("逆光剪影");
        assertThat(entry.location()).isEqualTo("图书馆天台");
        // 拍摄人与拍摄时间不接受输入，直接来自图库记录。
        assertThat(entry.photographerName()).isEqualTo("拍摄者" + EAST_MANAGER_ID);
        assertThat(entry.photographerStudentId()).isEqualTo("SID" + EAST_MANAGER_ID);
        assertThat(entry.takenAt()).isNotNull();
        assertThat(entry.photoTitle()).isEqualTo("日出");
        assertThat(entry.campusId()).isEqualTo(EAST_CAMPUS_ID);
        assertThat(entry.mine()).isTrue();
    }

    @Test
    void aManagerCannotSelectAnotherMembersGalleryPhoto() {
        long id = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "跨人选图", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), true, 10, List.of(), List.of()));
        long foreign = photo(WEST_MANAGER_ID, WEST_CAMPUS_ID, "别人的照片");

        assertThatThrownBy(() -> service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(foreign, "思路", "地点"), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void theSamePhotoCannotBeSubmittedTwiceButCanReturnAfterDeletion() {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        long photoId = photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "重复图");
        var entry = service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(photoId, "思路", "地点"), eastManager);

        assertThatThrownBy(() -> service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(photoId, "另一个思路", "另一个地点"), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

        // 条目是物理删除，因此同一张图片可以重新加入（唯一键不会被墓碑行占住）。
        service.deleteEntry(id, entry.id(), eastManager);
        assertThat(service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(photoId, "重来", "地点"), eastManager).id())
                .isNotNull();
    }

    @Test
    void perManagerEntryLimitIsEnforced() {
        long id = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "限两张", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), true, 2, List.of(), List.of()));
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "一"), "思路", "地点"), eastManager);
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "二"), "思路", "地点"), eastManager);
        long third = photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "三");

        assertThatThrownBy(() -> service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(third, "思路", "地点"), eastManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);

        // 上限是"每人"，另一位负责人不受影响。
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                photo(WEST_MANAGER_ID, WEST_CAMPUS_ID, "西一"), "思路", "地点"), westManager);
    }

    @Test
    void submissionWindowClosesBeforeStartAndAfterDeadline() {
        long notStarted = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "尚未开始", "<p>要求</p>", LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2), true, 10, List.of(), List.of()));
        long photoA = photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "早到");
        assertThatThrownBy(() -> service.addEntry(notStarted,
                new FeaturedCollectionService.EntryCommand(photoA, "思路", "地点"), eastManager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未开始");

        // 到点但定时任务还没跑到：接口也必须拒绝，否则截止时间形同虚设。
        long expired = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        jdbc.sql("UPDATE featured_collection SET starts_at=:start, ends_at=:end WHERE id=:id")
                .param("start", LocalDateTime.now().minusDays(2))
                .param("end", LocalDateTime.now().minusMinutes(1))
                .param("id", expired).update();
        long photoB = photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "迟到");
        assertThatThrownBy(() -> service.addEntry(expired,
                new FeaturedCollectionService.EntryCommand(photoB, "思路", "地点"), eastManager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过截止时间");
    }

    @Test
    void entriesBelongToTheirSubmitterOnly() {
        long id = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "各改各的", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), true, 10, List.of(), List.of()));
        var entry = service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "东图"), "思路", "地点"), eastManager);

        assertThatThrownBy(() -> service.updateEntry(id, entry.id(),
                new FeaturedCollectionService.EntryCommand(entry.photoId(), "改", "改"),
                entry.version(), westManager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        // 部长也不代填代改，他只负责发布与截止。
        assertThatThrownBy(() -> service.deleteEntry(id, entry.id(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        var updated = service.updateEntry(id, entry.id(),
                new FeaturedCollectionService.EntryCommand(entry.photoId(), "新思路", "新地点"),
                entry.version(), eastManager);
        assertThat(updated.idea()).isEqualTo("新思路");
        assertThat(updated.location()).isEqualTo("新地点");
    }

    @Test
    void closedCollectionsRejectFurtherEditing() {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        long photoId = photo(EAST_MANAGER_ID, EAST_CAMPUS_ID, "已截止");
        service.close(id, minister);

        assertThatThrownBy(() -> service.addEntry(id,
                new FeaturedCollectionService.EntryCommand(photoId, "思路", "地点"), eastManager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已截止");
    }

    // ------------------------------------------------------------------
    // 富文本要求
    // ------------------------------------------------------------------

    @Test
    void requirementHtmlIsSanitizedOnTheServer() {
        var created = service.create(new FeaturedCollectionService.CollectionCommand(
                "富文本要求",
                "<p>正文<script>alert(1)</script></p>"
                        + "<p onclick=\"steal()\">带事件</p>"
                        + "<img src=\"https://example.com/tracker.gif\">"
                        + "<img src=\"/api/v1/description-images/01HZZZZZZZZZZZZZZZZZZZZZZZ\">",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(1),
                true, 10, List.of(), List.of()), minister);

        String html = created.requirementHtml();
        // 注意不能直接断言 "script"：保留下来的合法图片路径里 description 一词就含这六个字母。
        assertThat(html).doesNotContain("<script").doesNotContain("alert(1)")
                .doesNotContain("onclick");
        // 外链图片被剔除，站内说明图片保留。
        assertThat(html).doesNotContain("example.com");
        assertThat(html).contains("/api/v1/description-images/01HZZZZZZZZZZZZZZZZZZZZZZZ");
        // 纯文本投影供列表摘要、检索和 Word 文档共用。
        assertThat(created.requirementText()).contains("正文").contains("带事件")
                .doesNotContain("alert");
    }

    // ------------------------------------------------------------------
    // Word 文档
    // ------------------------------------------------------------------

    @Test
    void documentSplitsChaptersByThePhotoCampusAndCarriesEveryField() throws Exception {
        long id = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "年度好图精选", "<p>请提交本学期最满意的作品</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), true, 10, List.of(), List.of()));
        long eastPhoto = photoWithObject(EAST_MANAGER_ID, EAST_CAMPUS_ID, "东校区日出");
        long westPhoto = photoWithObject(WEST_MANAGER_ID, WEST_CAMPUS_ID, "西校区夜景");
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                eastPhoto, "清晨守候两小时", "东校区图书馆"), eastManager);
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                westPhoto, "长曝光车轨", "西校区南门"), westManager);

        service.close(id, minister);
        documents.generate(id);

        FeaturedCollectionEntity saved = mapper.selectById(id);
        assertThat(saved.getDocumentStatus()).isEqualTo(FeaturedDocumentStatus.READY);
        assertThat(saved.getDocumentSize()).isPositive();

        String text = documentText(saved.getDocumentObjectKey());
        assertThat(text).contains("年度好图精选").contains("请提交本学期最满意的作品");
        // 按校区分章，两个校区各成一章。
        assertThat(text).contains("东校区").contains("西校区");
        assertThat(text).contains("东校区日出").contains("西校区夜景");
        assertThat(text).contains("清晨守候两小时").contains("东校区图书馆");
        assertThat(text).contains("长曝光车轨").contains("西校区南门");
        assertThat(text).contains("拍摄人").contains("拍摄时间").contains("拍摄地点").contains("拍摄思路");
        // 章节顺序按校区编码：FT-EAST 在 FT-WEST 之前。
        assertThat(text.indexOf("东校区日出")).isLessThan(text.indexOf("西校区夜景"));

        // 下载不设限：任何登录用户都能拿到签名地址与中文文件名。
        var download = service.document(id);
        assertThat(download.fileName()).isEqualTo("年度好图精选-好图精选.docx");
        assertThat(download.downloadUrl()).isNotBlank();
    }

    @Test
    void oneUnavailablePhotoDegradesToTextInsteadOfFailingTheWholeDocument() throws Exception {
        long id = publishedCollection(new FeaturedCollectionService.CollectionCommand(
                "缺图也要出稿", "<p>要求</p>", LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1), true, 10, List.of(), List.of()));
        long goodPhoto = photoWithObject(EAST_MANAGER_ID, EAST_CAMPUS_ID, "完好的图");
        long brokenPhoto = photoWithObject(WEST_MANAGER_ID, WEST_CAMPUS_ID, "会被删掉的图");
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                goodPhoto, "正常条目", "东校区"), eastManager);
        service.addEntry(id, new FeaturedCollectionService.EntryCommand(
                brokenPhoto, "文字仍在", "西校区"), westManager);
        // 填报之后图片被删除：条目保留，文档降级成纯文字条目。
        jdbc.sql("UPDATE photo SET deleted=1 WHERE id=:id").param("id", brokenPhoto).update();

        service.close(id, minister);
        documents.generate(id);

        FeaturedCollectionEntity saved = mapper.selectById(id);
        assertThat(saved.getDocumentStatus()).isEqualTo(FeaturedDocumentStatus.READY);
        String text = documentText(saved.getDocumentObjectKey());
        assertThat(text).contains("正常条目");
        assertThat(text).contains("文字仍在").contains("会被删掉的图");
        assertThat(text).contains("图片已从图库中删除");
    }

    @Test
    void documentIsStillProducedWhenNobodySubmitted() throws Exception {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        service.close(id, minister);
        documents.generate(id);

        FeaturedCollectionEntity saved = mapper.selectById(id);
        assertThat(saved.getDocumentStatus()).isEqualTo(FeaturedDocumentStatus.READY);
        assertThat(documentText(saved.getDocumentObjectKey())).contains("没有收到任何投稿");
    }

    @Test
    void documentDownloadIsRejectedUntilItIsReady() {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        assertThatThrownBy(() -> service.document(id))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未生成");
    }

    @Test
    void deadlineCloseUsesAConditionalUpdateSoItCannotRunTwice() {
        long id = publishedCollection(assignedCampuses(EAST_CAMPUS_ID));
        jdbc.sql("UPDATE featured_collection SET ends_at=:end WHERE id=:id")
                .param("end", LocalDateTime.now().minusMinutes(1)).param("id", id).update();

        assertThat(mapper.findDueForClose(LocalDateTime.now(), 50)).contains(id);
        assertThat(service.closeInternal(id, null, FeaturedCloseReason.DEADLINE)).isTrue();
        // 第二次（例如手动截止与定时任务撞在一起）不会再关闭，也就不会重复生成文档。
        assertThat(service.closeInternal(id, null, FeaturedCloseReason.DEADLINE)).isFalse();

        FeaturedCollectionEntity saved = mapper.selectById(id);
        assertThat(saved.getStatus()).isEqualTo(FeaturedCollectionStatus.CLOSED);
        assertThat(saved.getClosedReason()).isEqualTo(FeaturedCloseReason.DEADLINE);
        assertThat(saved.getClosedBy()).isNull();
        assertThat(mapper.findDueForClose(LocalDateTime.now(), 50)).doesNotContain(id);
    }

    @Test
    void softDeletedCollectionsDisappearFromEveryReadPath() {
        var created = service.create(command("将被删除"), minister);
        service.delete(created.id(), created.version(), minister);

        assertThatThrownBy(() -> service.get(created.id(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.list(1, 50, "将被删除", null, minister).items()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private FeaturedCollectionService.CollectionCommand command(String title) {
        return new FeaturedCollectionService.CollectionCommand(title, "<p>要求正文</p>",
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(3),
                true, 10, List.of(), List.of());
    }

    private FeaturedCollectionService.CollectionCommand assignedCampuses(Long... campusIds) {
        return new FeaturedCollectionService.CollectionCommand("校区指派精选", "<p>要求</p>",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1),
                false, 10, List.of(campusIds), List.of());
    }

    /**
     * 建好并立刻发布。刻意走 {@link FeaturedCollectionService#publish} 而不是直接改状态：
     * MyBatis 的一级缓存在同一个事务里会把 {@code findViewById} 的结果缓存下来，绕过
     * MyBatis 直接用 JdbcClient 改状态不会让它失效，后续读到的仍是草稿。
     */
    private long publishedCollection(FeaturedCollectionService.CollectionCommand command) {
        var created = service.create(command, minister);
        service.publish(created.id(), created.version(), minister);
        return created.id();
    }

    private void campus(long id, String code, String name) {
        jdbc.sql("INSERT INTO campus(id, code, name, enabled) VALUES (:id, :code, :name, TRUE)")
                .param("id", id).param("code", code).param("name", name).update();
    }

    private void user(long id, String username, String displayName, String role) {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', :displayName, :role, TRUE, FALSE)
                """).param("id", id).param("username", username)
                .param("displayName", displayName).param("role", role).update();
    }

    private void campusPermission(long userId, long campusId) {
        jdbc.sql("INSERT INTO user_campus_permission(user_id, campus_id) VALUES (:userId, :campusId)")
                .param("userId", userId).param("campusId", campusId).update();
    }

    /** 图库照片，没有真实对象——用于不涉及配图的用例。 */
    private long photo(long uploadedBy, long campusId, String title) {
        return insertPhoto(uploadedBy, campusId, title, null);
    }

    /** 图库照片，并在对象存储里放一张真实 JPEG，供文档生成读取。 */
    private long photoWithObject(long uploadedBy, long campusId, String title) throws Exception {
        String objectKey = "photos/test/featured-" + System.nanoTime() + ".jpg";
        byte[] jpeg = jpeg();
        storage.put(objectKey, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");
        return insertPhoto(uploadedBy, campusId, title, objectKey);
    }

    private long insertPhoto(long uploadedBy, long campusId, String title, String objectKey) {
        String key = objectKey == null ? "photos/test/missing-" + System.nanoTime() + ".jpg" : objectKey;
        jdbc.sql("""
                INSERT INTO photo
                    (title, photographer_student_id, photographer_name, uploaded_by, campus_id,
                     taken_at, width, height, size, content_type, object_key, sha256, status)
                VALUES (:title, :studentId, :name, :uploadedBy, :campusId, :takenAt,
                        1200, 800, 1024, 'image/jpeg', :objectKey, :sha256, 'AVAILABLE')
                """)
                .param("title", title)
                .param("studentId", "SID" + uploadedBy)
                .param("name", "拍摄者" + uploadedBy)
                .param("uploadedBy", uploadedBy)
                .param("campusId", campusId)
                .param("takenAt", LocalDateTime.now().minusDays(1))
                .param("objectKey", key)
                .param("sha256", String.format("%064d", Math.abs(key.hashCode() % 1_000_000)))
                .update();
        return jdbc.sql("SELECT id FROM photo WHERE object_key=:key").param("key", key)
                .query(Long.class).single();
    }

    private byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, 120, 80);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private String documentText(String objectKey) throws Exception {
        try (InputStream input = storage.open(objectKey);
             XWPFDocument document = new XWPFDocument(input)) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            return text.toString();
        }
    }
}
