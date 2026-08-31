package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.doc.mapper.DocNodeMapper;
import cn.photolib.doc.model.DocNodeEntity;
import cn.photolib.doc.model.DocNodeType;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.storage.ObjectStorageService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文档中心的业务规则。
 *
 * <p>重点在三处不能出错的地方：可见性（未登录看不到仅成员文档，包括它的插图）、
 * 拖拽落点（同级重排时序号必须排除被拖动的节点自身）、以及树结构的完整性
 * （不能把文件夹拖进自己的子树）。</p>
 */
@SpringBootTest
@Transactional
class DocServiceTests {
    private static final long MINISTER_ID = 9_901L;
    private static final byte[] PDF_BYTES = "%PDF-1.4 假装这是一份手册".getBytes(StandardCharsets.UTF_8);

    @Autowired private DocService service;
    @Autowired private DocNodeMapper nodeMapper;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'doc-minister', 'hash', '文档部长', 'MINISTER', TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        minister = new AuthenticatedUser(MINISTER_ID, "doc-minister", "文档部长",
                UserRole.MINISTER, null, false);
    }

    // ------------------------------------------------------------------
    // 结构
    // ------------------------------------------------------------------

    @Test
    void foldersHoldDocumentsAndDocumentsHoldNothing() {
        long guide = folder(null, "使用指南");
        long intro = document(guide, "快速上手");

        assertThat(service.tree()).singleElement()
                .satisfies(root -> {
                    assertThat(root.title()).isEqualTo("使用指南");
                    assertThat(root.children()).singleElement()
                            .satisfies(child -> assertThat(child.id()).isEqualTo(intro));
                });

        // 文档不是容器：往里放东西必须被挡住，否则树会长出没人能渲染的分支。
        assertThatThrownBy(() -> service.create(intro, DocNodeType.DOCUMENT, "子文档", minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void siblingsCannotShareANameButFoldersAndDocumentsCan() {
        folder(null, "指南");
        assertThatThrownBy(() -> service.create(null, DocNodeType.FOLDER, " 指南 ", minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        // 同名的文件夹和文档可以共存，和 Obsidian 里"文件夹 A"与"A.md"并存的手感一致。
        assertThat(service.create(null, DocNodeType.DOCUMENT, "指南", minister).focusId()).isNotNull();
    }

    @Test
    void aFolderCannotBeMovedIntoItsOwnSubtree() {
        long outer = folder(null, "外层");
        long inner = folder(outer, "内层");
        int version = version(outer);

        assertThatThrownBy(() -> service.move(outer, inner, 0, version, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
        // 移动被拒绝之后结构必须原样保留，而不是留下一半改动。
        assertThat(nodeMapper.selectById(outer).getParentId()).isNull();
        assertThat(nodeMapper.selectById(inner).getParentId()).isEqualTo(outer);
    }

    @Test
    void movingWithinTheSameParentRenumbersTheWholeRow() {
        long first = document(null, "第一篇");
        long second = document(null, "第二篇");
        long third = document(null, "第三篇");

        // 把第一篇挪到最后一位。服务端按"排除自身后的序号"理解 index=2。
        service.move(first, null, 2, version(first), minister);

        assertThat(service.tree()).extracting(DocService.ManageNode::id)
                .containsExactly(second, third, first);
        // 顺序必须真的落到 sort_order 上，而不是只在这一次返回里排对。
        assertThat(service.tree()).extracting(DocService.ManageNode::sortOrder)
                .containsExactly(0, 1, 2);
    }

    @Test
    void movingIntoAFolderLeavesNoHoleBehind() {
        long folder = folder(null, "归档");
        long first = document(null, "第一篇");
        long second = document(null, "第二篇");

        service.move(first, folder, 0, version(first), minister);

        List<DocService.ManageNode> tree = service.tree();
        assertThat(tree).extracting(DocService.ManageNode::id).containsExactly(folder, second);
        // 老位置压实：第二篇补到 1 号位（归档占 0 号位），不留空洞。
        assertThat(tree).extracting(DocService.ManageNode::sortOrder).containsExactly(0, 1);
        assertThat(tree.get(0).children()).extracting(DocService.ManageNode::id).containsExactly(first);
    }

    @Test
    void deletingAFolderTakesItsWholeSubtreeWithIt() {
        long outer = folder(null, "外层");
        long inner = folder(outer, "内层");
        long leaf = document(inner, "深处的文档");

        service.delete(outer, version(outer), minister);

        assertThat(service.tree()).isEmpty();
        assertThat(nodeMapper.selectById(inner)).isNull();
        assertThat(nodeMapper.selectById(leaf)).isNull();
    }

    // ------------------------------------------------------------------
    // 发布与可见性
    // ------------------------------------------------------------------

    @Test
    void anEmptyDocumentCannotBePublished() {
        long id = document(null, "还没写");
        assertThatThrownBy(() -> service.setPublished(id, true, version(id), minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void newDocumentsRequireLoginUntilSomeoneSaysOtherwise() {
        long id = document(null, "默认可见范围");
        assertThat(nodeMapper.selectById(id).getVisibility()).isEqualTo(DocVisibility.MEMBERS);
    }

    @Test
    void anonymousReadersOnlySeePublicDocuments() {
        long open = publishedDocument(null, "公开说明", "# 公开\n谁都能看", DocVisibility.PUBLIC);
        long members = publishedDocument(null, "内部说明", "# 内部\n登录才能看", DocVisibility.MEMBERS);

        assertThat(service.readerTree(false)).extracting(DocService.ReaderNode::title)
                .containsExactly("公开说明");
        assertThat(service.readerTree(true)).extracting(DocService.ReaderNode::title)
                .containsExactlyInAnyOrder("公开说明", "内部说明");

        String openPublicId = nodeMapper.selectById(open).getPublicId();
        String membersPublicId = nodeMapper.selectById(members).getPublicId();

        assertThat(service.readerDocument(openPublicId, false).content()).contains("谁都能看");
        // 直链打开仅成员文档：明确回"要登录"，而不是含糊的 404——
        // 成员把链接发给同学时，这个区别决定了对方知不知道该做什么。
        assertThatThrownBy(() -> service.readerDocument(membersPublicId, false))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(service.readerDocument(membersPublicId, true).content()).contains("登录才能看");
        assertThat(service.readerDocument(membersPublicId, true).requiresLogin()).isTrue();
    }

    @Test
    void unpublishedDocumentsAreInvisibleToEveryReaderEvenWhenLoggedIn() {
        long draft = document(null, "草稿");
        service.saveContent(draft, "写了一半", version(draft), minister);
        String publicId = nodeMapper.selectById(draft).getPublicId();

        assertThat(service.readerTree(true)).isEmpty();
        // 未发布对所有读者都是 404：草稿不该因为"你已经登录了"就漏出去。
        assertThatThrownBy(() -> service.readerDocument(publicId, true))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void foldersAppearOnlyWhenTheirSubtreeHasSomethingTheReaderCanSee() {
        long folder = folder(null, "内部资料");
        publishedDocument(folder, "内部文档", "只给成员看", DocVisibility.MEMBERS);

        // 未登录：文件夹里没有它能看的东西，整个文件夹都不该出现，
        // 否则点开一个空文件夹会让人以为系统坏了。
        assertThat(service.readerTree(false)).isEmpty();
        assertThat(service.readerTree(true)).singleElement()
                .satisfies(node -> assertThat(node.children()).hasSize(1));
    }

    @Test
    void switchingVisibilityDoesNotUnpublishTheDocument() {
        long id = publishedDocument(null, "两个开关互不影响", "正文", DocVisibility.PUBLIC);
        service.setVisibility(id, DocVisibility.MEMBERS, version(id), minister);

        assertThat(nodeMapper.selectById(id).getPublished()).isTrue();
        assertThat(nodeMapper.selectById(id).getVisibility()).isEqualTo(DocVisibility.MEMBERS);
    }

    // ------------------------------------------------------------------
    // 正文与插图
    // ------------------------------------------------------------------

    @Test
    void contentLivesInObjectStorageAndTheSummaryStaysInTheDatabase() {
        long id = document(null, "带正文的文档");
        service.saveContent(id, "# 标题\n\n![图](/x) 正文的第一句话", version(id), minister);

        String objectKey = nodeMapper.selectById(id).getObjectKey();
        assertThat(objectKey).isEqualTo("docs/" + nodeMapper.selectById(id).getPublicId() + "/content.md");
        assertThat(read(objectKey)).contains("正文的第一句话");
        // 摘要是给目录用的纯文本投影：标记去掉，图片换成占位符。
        assertThat(nodeMapper.selectById(id).getSummary())
                .isEqualTo("标题 [图片] 正文的第一句话");
    }

    @Test
    void aMissingContentObjectRendersAsEmptyInsteadOfFailingThePage() {
        long id = publishedDocument(null, "对象丢了", "原本的正文", DocVisibility.PUBLIC);
        // 模拟"数据库回滚到了写正文之后、对象却已不在"这种运维事故。
        storage.delete(nodeMapper.selectById(id).getObjectKey());

        String publicId = nodeMapper.selectById(id).getPublicId();
        assertThat(service.readerDocument(publicId, false).content()).isEmpty();
    }

    @Test
    void assetsInheritTheVisibilityOfTheDocumentTheyBelongTo() throws IOException {
        long id = document(null, "带插图的文档");
        service.saveContent(id, "正文", version(id), minister);
        DocService.AssetUploaded asset = service.uploadAsset(id, pngUpload(), minister);
        assertThat(asset.url()).isEqualTo(DocService.ASSET_URL_PREFIX + asset.id());

        // 未发布：谁都读不到，包括已登录的读者。
        assertThatThrownBy(() -> service.readerAsset(asset.id(), true))
                .isInstanceOf(BusinessException.class);

        service.setPublished(id, true, version(id), minister);
        // 已发布但仅限成员：匿名直链必须被拒，否则把图片地址发出去就绕过了登录要求。
        assertThatThrownBy(() -> service.readerAsset(asset.id(), false))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.readerAsset(asset.id(), true).getNodeId()).isEqualTo(id);

        service.setVisibility(id, DocVisibility.PUBLIC, version(id), minister);
        assertThat(service.readerAsset(asset.id(), false).getNodeId()).isEqualTo(id);
    }

    // ------------------------------------------------------------------
    // PDF 文档
    // ------------------------------------------------------------------

    @Test
    void aPdfBecomesADraftDocumentWithItsFileInObjectStorage() throws IOException {
        long id = service.createPdf(null, "入部须知.pdf", pdfUpload(), minister).focusId();
        DocNodeEntity node = nodeMapper.selectById(id);

        assertThat(node.getNodeType()).isEqualTo(DocNodeType.PDF);
        // 传上来就是草稿、就是仅成员可见：和 Markdown 文档同一条安全默认值。
        assertThat(node.getPublished()).isFalse();
        assertThat(node.getVisibility()).isEqualTo(DocVisibility.MEMBERS);
        assertThat(node.getObjectKey()).isEqualTo("docs/" + node.getPublicId() + "/document.pdf");
        assertThat(read(node.getObjectKey())).startsWith("%PDF-");
        assertThat(node.getContentSize()).isEqualTo(PDF_BYTES.length);
    }

    @Test
    void aPdfNodeCannotBeCreatedWithoutItsFile() {
        // 没有文件的 PDF 节点既发布不了也预览不了，只会挂在目录里当死链。
        assertThatThrownBy(() -> service.create(null, DocNodeType.PDF, "空壳", minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void bytesThatAreNotAPdfAreRejectedNoMatterWhatTheContentTypeClaims() {
        MockMultipartFile disguised = new MockMultipartFile(
                "file", "伪装.pdf", "application/pdf", "GIF89a not a pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.createPdf(null, "伪装", disguised, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
        // 被拒的上传不能留下节点。
        assertThat(service.tree()).isEmpty();
    }

    @Test
    void aPdfDocumentObeysTheSamePublishAndVisibilitySwitchesAsMarkdown() throws IOException {
        long id = service.createPdf(null, "内部流程", pdfUpload(), minister).focusId();
        String publicId = nodeMapper.selectById(id).getPublicId();

        // 草稿：所有读者都看不到，包括已登录的。
        assertThat(service.readerTree(true)).isEmpty();
        assertThatThrownBy(() -> service.readerPdf(publicId, true))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        service.setPublished(id, true, version(id), minister);

        // 已发布但仅限成员：目录里对匿名访客整条隐藏，文件直链同样被拒——
        // PDF 的直链就是它的正文，判松一格等于把内部文件放上公网。
        assertThat(service.readerTree(false)).isEmpty();
        assertThatThrownBy(() -> service.readerPdf(publicId, false))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(service.readerTree(true)).extracting(DocService.ReaderNode::nodeType)
                .containsExactly(DocNodeType.PDF);
        assertThat(service.readerPdf(publicId, true).getId()).isEqualTo(id);

        service.setVisibility(id, DocVisibility.PUBLIC, version(id), minister);
        assertThat(service.readerPdf(publicId, false).getId()).isEqualTo(id);
    }

    @Test
    void openingAPdfDocumentGivesAFileLinkInsteadOfMarkdownContent() throws IOException {
        long id = service.createPdf(null, "手册", pdfUpload(), minister).focusId();
        service.setPublished(id, true, version(id), minister);
        String publicId = nodeMapper.selectById(id).getPublicId();

        DocService.ReaderDocument opened = service.readerDocument(publicId, true);

        assertThat(opened.nodeType()).isEqualTo(DocNodeType.PDF);
        // 正文必须是空串而不是把 PDF 字节按 UTF-8 读出来的一堆乱码。
        assertThat(opened.content()).isEmpty();
        assertThat(opened.fileUrl()).isEqualTo("/api/v1/public/docs/" + publicId + "/file");
        assertThat(opened.requiresLogin()).isTrue();
    }

    @Test
    void replacingThePdfKeepsTheLinkAndRejectsAStaleVersion() throws IOException {
        long id = service.createPdf(null, "会更新的手册", pdfUpload(), minister).focusId();
        String objectKey = nodeMapper.selectById(id).getObjectKey();

        service.replacePdf(id, pdfUpload("%PDF-1.7 第二版"), version(id), minister);

        // 对象键跟着 publicId 走，所以读者手上的链接继续有效。
        assertThat(nodeMapper.selectById(id).getObjectKey()).isEqualTo(objectKey);
        assertThat(read(objectKey)).contains("第二版");
        assertThatThrownBy(() -> service.replacePdf(id, pdfUpload(), 1, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void aPdfHasNoMarkdownBodyToEdit() throws IOException {
        long id = service.createPdf(null, "只读的手册", pdfUpload(), minister).focusId();
        assertThatThrownBy(() -> service.saveContent(id, "试图写正文", version(id), minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void aPdfAndAMarkdownDocumentCannotShareANameInTheSameFolder() throws IOException {
        document(null, "同名的东西");
        // 两种叶子在目录里长得一样，同名的话读者分不出点开的是哪个。
        MockMultipartFile file = pdfUpload();
        assertThatThrownBy(() -> service.createPdf(null, "同名的东西", file, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        // 文件夹仍然可以同名，Obsidian 式的手感没变。
        assertThat(service.create(null, DocNodeType.FOLDER, "同名的东西", minister).focusId()).isNotNull();
    }

    @Test
    void staleVersionsAreRejectedInsteadOfOverwritingSomeoneElsesEdit() {
        long id = document(null, "并发编辑");
        service.saveContent(id, "第一次保存", version(id), minister);

        assertThatThrownBy(() -> service.saveContent(id, "拿着旧版本号回来的第二次保存", 1, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
        assertThat(read(nodeMapper.selectById(id).getObjectKey())).isEqualTo("第一次保存");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private long folder(Long parentId, String title) {
        return service.create(parentId, DocNodeType.FOLDER, title, minister).focusId();
    }

    private long document(Long parentId, String title) {
        return service.create(parentId, DocNodeType.DOCUMENT, title, minister).focusId();
    }

    private long publishedDocument(Long parentId, String title, String content, DocVisibility visibility) {
        long id = document(parentId, title);
        service.saveContent(id, content, version(id), minister);
        service.setPublished(id, true, version(id), minister);
        service.setVisibility(id, visibility, version(id), minister);
        return id;
    }

    private int version(long id) {
        return nodeMapper.selectById(id).getVersion();
    }

    private String read(String objectKey) {
        try (InputStream input = storage.open(objectKey)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 最小的合法 PDF：够通过文件头校验即可——这里验的是规则，不是 PDF 解析。 */
    private MockMultipartFile pdfUpload() {
        return pdfUpload(new String(PDF_BYTES, StandardCharsets.UTF_8));
    }

    private MockMultipartFile pdfUpload(String content) {
        return new MockMultipartFile("file", "文档.pdf", "application/pdf",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** 最小的合法 PNG 头，够通过魔数校验即可——这里验的是授权，不是图像解码。 */
    private MockMultipartFile pngUpload() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4};
        return new MockMultipartFile("file", "插图.png", "image/png", png);
    }
}
