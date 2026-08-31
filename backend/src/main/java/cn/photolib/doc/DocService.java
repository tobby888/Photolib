package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.InlineImageUpload;
import cn.photolib.common.upload.PdfUpload;
import cn.photolib.common.util.PublicId;
import cn.photolib.doc.mapper.DocAssetMapper;
import cn.photolib.doc.mapper.DocNodeMapper;
import cn.photolib.doc.model.DocAssetEntity;
import cn.photolib.doc.model.DocNodeEntity;
import cn.photolib.doc.model.DocNodeType;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档中心。
 *
 * <p>两条完全不同的访问路径，不要把它们的规则混在一起：</p>
 * <ul>
 *   <li><b>编辑</b>需要 {@code DOC_MANAGE}（默认只有管理员和部长有），
 *       由控制器上的 {@code @PreAuthorize} 把关；</li>
 *   <li><b>阅读</b>走 {@code /public/docs/**}，不需要登录也能访问接口本身，
 *       并受 {@link DocRateLimiter} 限速。</li>
 * </ul>
 *
 * <p><b>可见性是两个正交的开关，判定时必须同时满足</b>（{@link #visibleTo}）：
 * {@code published} 决定"是不是草稿"，{@code visibility} 决定"要不要登录"。
 * 部长可以把某篇文档设为 {@code MEMBERS}，未登录的人就读不到它——
 * 目录里不列出，直链打开返回一个"请先登录"的 403，插图直链同样拒绝
 * （见 {@code DocAssetMapper.findReadable}）。三处判定必须同时成立才算真正挡住，
 * 少一处就等于把内容从另一个门放了出去。</p>
 *
 * <p><b>叶子有两种，规则完全一样。</b>{@code DOCUMENT} 的正文是 Markdown，
 * {@code PDF} 的"正文"就是上传的那份文件；发布、可见范围、目录剪枝、限速
 * 全部共用同一套判定。因此凡是判断"是不是叶子"的地方都写
 * {@code !nodeType.isFolder()}——写死 {@code == DOCUMENT} 会让 PDF 悄悄漏出目录，
 * 或者永远发布不了。反过来，读写 Markdown 正文的两处（{@link #saveContent}、
 * {@link #readContent}）必须收紧到 {@code DOCUMENT}：PDF 的 object_key 指向二进制。</p>
 *
 * <p><b>正文不在数据库里。</b>Markdown 正文、插图和 PDF 都在对象存储：
 * {@code docs/{publicId}/content.md}、{@code docs/assets/{assetId}.{ext}}
 * 与 {@code docs/{publicId}/document.pdf}。
 * 数据库只存 object_key 和用于列表的摘要。由此产生一条必须记住的运维事实：
 * 数据库备份/回滚不会同步对象存储，回滚之后正文可能比元数据新或旧。
 * 因此 {@link #readContent} 读不到对象时返回空正文并告警，绝不抛 500——
 * 一次错位的回滚不该让整个公开文档站变白屏。</p>
 *
 * <p><b>文件夹没有这两个开关。</b>一个文件夹是否出现在某位读者的目录里，
 * 取决于它的子树里有没有对这位读者可见的文档（见 {@link #buildReader}）。
 * 这条规则是有意的：让文件夹也带开关，就会出现"文档发布了但祖先文件夹忘了发布"
 * 这种无声故障，作者在页面上找不到自己刚发布的文档却看不出原因。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocService {
    /** 整棵树的节点数上限。这是人手维护的目录，不是内容管理系统。 */
    static final int MAX_NODES = 1_000;
    /** 目录层级上限（根节点算第 1 层）。深过这个数，侧边栏就没法看了。 */
    static final int MAX_DEPTH = 6;
    public static final int MAX_CONTENT_CHARS = 100_000;
    /** UTF-8 一个字符最多 4 字节：读回时按此截断，防止被改坏的对象撑爆内存。 */
    private static final int MAX_CONTENT_BYTES = 4 * MAX_CONTENT_CHARS;
    private static final int SUMMARY_CHARS = 200;
    private static final String CONTENT_TYPE = "text/markdown; charset=UTF-8";
    /** 插图的公开地址前缀；写进正文的 Markdown 里，必须和公开控制器的映射一致。 */
    public static final String ASSET_URL_PREFIX = "/api/v1/public/docs/assets/";
    /** PDF 文档的公开地址前后缀，拼出来必须和 {@code DocReaderController} 的映射一致。 */
    public static final String FILE_URL_PREFIX = "/api/v1/public/docs/";
    public static final String FILE_URL_SUFFIX = "/file";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final DocNodeMapper nodeMapper;
    private final DocAssetMapper assetMapper;
    private final ObjectStorageService storage;

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 编辑视角的整棵树：草稿和已发布的都在。 */
    public List<ManageNode> tree() {
        return buildManage(byParent(nodeMapper.findAll()), null, 1);
    }

    public DocumentDetail get(long id) {
        DocNodeEntity node = requireNode(id);
        return new DocumentDetail(toManage(node, List.of()), readContent(node), breadcrumb(node));
    }

    /**
     * 读者视角的目录。未登录时只列出 PUBLIC 文档，登录后 MEMBERS 文档也一并列出。
     *
     * <p>未登录时 MEMBERS 文档是<b>整条隐藏</b>的，连标题都不给：标题本身也可能是
     * 内部信息（"某某活动预算说明"），把它列出来再标个锁，等于把一份内部目录
     * 挂在了公网上。</p>
     */
    public List<ReaderNode> readerTree(boolean authenticated) {
        return buildReader(byParent(nodeMapper.findAll()), null, 1, authenticated);
    }

    /**
     * 按 publicId 打开一篇文档。
     *
     * <p>三种失败刻意区分开：不存在和未发布都返回 404（否则这个接口就成了
     * "猜 publicId 是否存在"的探针）；而<b>已发布但需要登录</b>返回 403 并说明原因——
     * 成员把链接分享给还没登录的同学时，"请先登录"比"文档不存在"有用得多，
     * 而 26 位随机 publicId 本身不可枚举，所以这点区分不构成可利用的信息泄漏。</p>
     *
     * <p>这里用 403 而不是语义上更贴切的 401，是为了避开前端的令牌刷新逻辑：
     * {@code api.ts} 的响应拦截器见到 401 会先去 {@code /auth/refresh} 重试一次，
     * 匿名访客身上这一次重试注定失败，还会顺带触发一次"会话过期"广播。</p>
     */
    public ReaderDocument readerDocument(String publicId, boolean authenticated) {
        DocNodeEntity node = publicId == null ? null : nodeMapper.findByPublicId(publicId.trim());
        if (node == null || node.getNodeType().isFolder()
                || !Boolean.TRUE.equals(node.getPublished())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在或尚未发布");
        }
        if (!visibleTo(node, authenticated)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该文档需要登录后查看，请先登录");
        }
        boolean pdf = node.getNodeType() == DocNodeType.PDF;
        return new ReaderDocument(node.getPublicId(), node.getNodeType(), node.getTitle(),
                pdf ? "" : readContent(node), pdf ? fileUrl(node) : null,
                pdf ? node.getContentSize() : null,
                requiresLogin(node), node.getUpdatedAt(), node.getUpdaterDisplayName(),
                breadcrumb(node));
    }

    /**
     * 取一份 PDF 文档本身，供读者接口按流回吐。可见性判定和 {@link #readerDocument}
     * 完全一样，而且必须一样：PDF 的直链就是它的正文，判松一格等于把仅限成员的
     * 文件放到了公网上。
     */
    public DocNodeEntity readerPdf(String publicId, boolean authenticated) {
        DocNodeEntity node = publicId == null ? null : nodeMapper.findByPublicId(publicId.trim());
        if (node == null || node.getNodeType() != DocNodeType.PDF
                || !Boolean.TRUE.equals(node.getPublished())
                || !StringUtils.hasText(node.getObjectKey())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在或尚未发布");
        }
        if (!visibleTo(node, authenticated)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该文档需要登录后查看，请先登录");
        }
        return node;
    }

    /** 编辑视角取 PDF：草稿也要能预览，所以这里不看发布状态，授权由控制器的 DOC_MANAGE 兜底。 */
    public DocNodeEntity managedPdf(long id) {
        DocNodeEntity node = requireNode(id);
        if (node.getNodeType() != DocNodeType.PDF || !StringUtils.hasText(node.getObjectKey())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "这个节点没有 PDF 文件");
        }
        return node;
    }

    public InputStream openNode(DocNodeEntity node) {
        return storage.open(node.getObjectKey());
    }

    /**
     * 插图。可见性完全跟随所属文档：未发布、或者需要登录而调用方未登录，都查不到。
     * 这一条是安全边界——插图直链绝不能成为绕过登录要求的旁路。
     */
    public DocAssetEntity readerAsset(String assetId, boolean authenticated) {
        DocAssetEntity asset = assetId == null ? null
                : assetMapper.findReadable(assetId.trim(), authenticated);
        if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或不可访问");
        return asset;
    }

    public InputStream openAsset(DocAssetEntity asset) {
        return storage.open(asset.getObjectKey());
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    @Transactional
    public TreeMutation create(Long parentId, DocNodeType nodeType, String title,
                               AuthenticatedUser user) {
        // PDF 节点没有"先建个空的、回头再写"这一步：它的正文就是那份文件，
        // 一个没有文件的 PDF 节点既发布不了也预览不了，只会挂在目录里当死链，
        // 所以它只能由 createPdf 带着文件一起建出来。
        if (nodeType == DocNodeType.PDF) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "PDF 文档请通过上传文件创建");
        }
        if (nodeMapper.countAll() >= MAX_NODES) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "文档数量已达上限（" + MAX_NODES + "），请先清理不再需要的内容");
        }
        String cleanTitle = normalizeTitle(title);
        if (parentId != null) {
            DocNodeEntity parent = requireNode(parentId);
            requireFolder(parent);
            if (depthOf(parent) + 1 > MAX_DEPTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "目录层级最多 " + MAX_DEPTH + " 层");
            }
        }
        requireUniqueTitle(parentId, cleanTitle, nodeType, null);
        DocNodeEntity node = new DocNodeEntity();
        node.setPublicId(PublicId.next());
        node.setParentId(parentId);
        node.setNodeType(nodeType);
        node.setTitle(cleanTitle);
        node.setSortOrder(nodeMapper.maxSortOrder(parentId) + 1);
        node.setPublished(false);
        // 显式写死默认值，不依赖数据库的 DEFAULT：MyBatis-Plus 只是碰巧会跳过 null 字段，
        // 而"新文档默认需要登录"是一条安全默认值，不该挂在插入策略的配置上。
        node.setVisibility(DocVisibility.MEMBERS);
        node.setCreatedBy(user.id());
        node.setUpdatedBy(user.id());
        nodeMapper.insert(node);
        return new TreeMutation(tree(), node.getId());
    }

    @Transactional
    public TreeMutation rename(long id, String title, int version, AuthenticatedUser user) {
        DocNodeEntity node = requireNode(id);
        String cleanTitle = normalizeTitle(title);
        requireUniqueTitle(node.getParentId(), cleanTitle, node.getNodeType(), id);
        requireUpdated(nodeMapper.updateTitle(id, cleanTitle, user.id(), version, LocalDateTime.now()));
        return new TreeMutation(tree(), id);
    }

    /**
     * 保存正文。
     *
     * <p>顺序是"先过乐观锁，再写对象"，不能颠倒：先写对象的话，一次版本冲突会把
     * 别人刚保存的正文覆盖掉，而调用方只看到一个 409。反过来写，如果对象存储失败，
     * 事务回滚，数据库和对象都停在旧内容上。</p>
     *
     * <p>仍有一个已知的窄窗口：CAS 成功、对象写成功，但事务在提交时失败——
     * 此时对象已是新正文而元数据（摘要、大小、修改人）回到旧值。正文本身不会丢，
     * 再保存一次即可修正。为此没有引入两阶段写：代价远大于收益。</p>
     */
    @Transactional
    public DocumentDetail saveContent(long id, String content, int version, AuthenticatedUser user) {
        DocNodeEntity node = requireNode(id);
        if (node.getNodeType() != DocNodeType.DOCUMENT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    node.getNodeType() == DocNodeType.PDF
                            ? "PDF 文档没有可编辑的正文，请直接替换文件"
                            : "文件夹没有正文");
        }
        String text = content == null ? "" : content;
        if (text.length() > MAX_CONTENT_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "正文不能超过 " + MAX_CONTENT_CHARS + " 个字符");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String objectKey = "docs/" + node.getPublicId() + "/content.md";
        requireUpdated(nodeMapper.updateContent(id, objectKey, bytes.length,
                DocMarkdown.summary(text, SUMMARY_CHARS), user.id(), version, LocalDateTime.now()));
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, CONTENT_TYPE);
        DocNodeEntity saved = requireNode(id);
        return new DocumentDetail(toManage(saved, List.of()), text, breadcrumb(saved));
    }

    @Transactional
    public TreeMutation setPublished(long id, boolean published, int version, AuthenticatedUser user) {
        DocNodeEntity node = requireNode(id);
        if (node.getNodeType().isFolder()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "只有文档需要发布；文件夹会在它下面有已发布文档时自动出现在公开目录里");
        }
        if (published && !StringUtils.hasText(node.getObjectKey())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "文档还没有正文，先写点内容再发布");
        }
        LocalDateTime now = LocalDateTime.now();
        requireUpdated(nodeMapper.updatePublished(id, published, published ? now : null,
                user.id(), version, now));
        return new TreeMutation(tree(), id);
    }

    /**
     * 指定这篇文档要不要登录才能看。
     *
     * <p>和发布是两个独立开关，所以是独立的接口：把一篇已发布的文档改成
     * {@code MEMBERS} 不应该顺带把它退回草稿，反过来也一样。改动立刻生效，
     * 包括正文、目录里的条目和文档里的插图——三处都在读取时按同一个
     * {@link #visibleTo} 判定，没有缓存需要失效。</p>
     */
    @Transactional
    public TreeMutation setVisibility(long id, DocVisibility visibility, int version,
                                      AuthenticatedUser user) {
        DocNodeEntity node = requireNode(id);
        if (node.getNodeType().isFolder()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "只有文档能设置可见范围；文件夹跟随它下面的文档");
        }
        if (visibility == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择可见范围");
        }
        requireUpdated(nodeMapper.updateVisibility(id, visibility.name(), user.id(), version,
                LocalDateTime.now()));
        return new TreeMutation(tree(), id);
    }

    /**
     * 拖拽移动 + 同级排序。{@code index} 是在目标父节点下的目标位置（0 起）。
     * 服务端把整组兄弟重写成 0..n-1，所以前端只要报位置，不需要自己算序号。
     */
    @Transactional
    public TreeMutation move(long id, Long newParentId, int index, int version,
                             AuthenticatedUser user) {
        DocNodeEntity node = requireNode(id);
        List<DocNodeEntity> all = nodeMapper.findAll();
        Map<Long, List<DocNodeEntity>> children = byParent(all);
        if (newParentId != null) {
            DocNodeEntity parent = all.stream()
                    .filter(candidate -> newParentId.equals(candidate.getId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "目标文件夹不存在"));
            requireFolder(parent);
            // 环检测必须在写之前做：数据库的外键拦不住 A→B→A 这种循环，
            // 一旦写进去，任何一次树遍历都会栈溢出。
            if (newParentId == id || isDescendant(children, id, newParentId)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能把文件夹移动到它自己的子目录里");
            }
            if (depthOf(parent) + heightOf(children, id) > MAX_DEPTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "移动后目录层级会超过 " + MAX_DEPTH + " 层");
            }
        }
        requireUniqueTitle(newParentId, node.getTitle(), node.getNodeType(), id);

        List<DocNodeEntity> siblings = new ArrayList<>(children.getOrDefault(newParentId, List.of()));
        siblings.removeIf(sibling -> sibling.getId() == id);
        int position = Math.max(0, Math.min(index, siblings.size()));
        LocalDateTime now = LocalDateTime.now();
        requireUpdated(nodeMapper.moveNode(id, newParentId, position, user.id(), version, now));

        List<Long> ordered = new ArrayList<>();
        for (int cursor = 0; cursor < siblings.size(); cursor++) {
            if (cursor == position) ordered.add(id);
            ordered.add(siblings.get(cursor).getId());
        }
        if (position >= siblings.size()) ordered.add(id);
        renumber(ordered, now);
        // 换了父节点时，老位置留下的空洞也压实一遍。顺序不受影响，
        // 但留着洞会让下一次"插到第 n 位"的调试结果看起来莫名其妙。
        if (!Objects.equals(node.getParentId(), newParentId)) {
            renumber(children.getOrDefault(node.getParentId(), List.<DocNodeEntity>of()).stream()
                    .map(DocNodeEntity::getId).filter(sibling -> sibling != id).toList(), now);
        }
        return new TreeMutation(tree(), id);
    }

    /**
     * 删除节点及其整棵子树（软删）。
     *
     * <p>对象存储里的正文和插图刻意保留：软删是可撤销的，把对象一并删掉会让
     * "从数据库回滚恢复一篇误删的文档"变成不可能。孤儿对象由人工或后续清理任务处理，
     * 它们不会被任何接口读到——公开插图查询要 join 到未删除且已发布的文档。</p>
     */
    @Transactional
    public TreeMutation delete(long id, int version, AuthenticatedUser user) {
        DocNodeEntity node = requireNode(id);
        LocalDateTime now = LocalDateTime.now();
        requireUpdated(nodeMapper.softDelete(id, version, now));
        Map<Long, List<DocNodeEntity>> children = byParent(nodeMapper.findAll());
        for (long descendant : descendants(children, id)) {
            nodeMapper.softDeleteCascade(descendant, now);
        }
        renumber(children.getOrDefault(node.getParentId(), List.<DocNodeEntity>of()).stream()
                .map(DocNodeEntity::getId).filter(sibling -> sibling != id).toList(), now);
        return new TreeMutation(tree(), null);
    }

    /**
     * 直接上传一份 PDF 作为文档。
     *
     * <p>和 Markdown 文档的区别只在正文放在哪：这里的"正文"就是上传的文件本身，
     * 所以节点和文件必须一次建出来（{@link #create} 因此拒绝 PDF 类型）。
     * 建出来的节点仍是草稿、仍默认 {@code MEMBERS}，发布和可见范围走的还是
     * 原来那两个开关——读者那边不该因为格式不同就多出一套规则。</p>
     *
     * <p>顺序是"先插数据库、再写对象"：写对象失败会让事务回滚，不留下一个
     * 指向空对象的节点。反过来（先写对象再插）则要靠 catch 里的补偿删除，
     * 而那次删除本身也可能失败。已知的窄窗口和 {@link #saveContent} 相同——
     * 对象写成功但提交失败会留下一个孤儿对象，它不被任何接口引用。</p>
     *
     * <p>文件不读进内存：{@link MultipartFile#getInputStream()} 原样交给对象存储，
     * 校验只看文件头（见 {@link PdfUpload}）。一份 50 MiB 的 byte[] 乘上几个
     * 并发上传就足以把后端打挂。</p>
     */
    @Transactional
    public TreeMutation createPdf(Long parentId, String title, MultipartFile file,
                                  AuthenticatedUser user) throws IOException {
        PdfUpload.validate(file);
        if (nodeMapper.countAll() >= MAX_NODES) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "文档数量已达上限（" + MAX_NODES + "），请先清理不再需要的内容");
        }
        String cleanTitle = normalizeTitle(title);
        if (parentId != null) {
            DocNodeEntity parent = requireNode(parentId);
            requireFolder(parent);
            if (depthOf(parent) + 1 > MAX_DEPTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "目录层级最多 " + MAX_DEPTH + " 层");
            }
        }
        requireUniqueTitle(parentId, cleanTitle, DocNodeType.PDF, null);
        DocNodeEntity node = new DocNodeEntity();
        node.setPublicId(PublicId.next());
        node.setParentId(parentId);
        node.setNodeType(DocNodeType.PDF);
        node.setTitle(cleanTitle);
        node.setSortOrder(nodeMapper.maxSortOrder(parentId) + 1);
        node.setPublished(false);
        node.setVisibility(DocVisibility.MEMBERS);
        node.setObjectKey(pdfObjectKey(node.getPublicId()));
        node.setContentSize(file.getSize());
        node.setCreatedBy(user.id());
        node.setUpdatedBy(user.id());
        nodeMapper.insert(node);
        storePdf(node.getObjectKey(), file);
        return new TreeMutation(tree(), node.getId());
    }

    /**
     * 换掉一份已有 PDF 文档的文件。对象键不变（跟着 publicId 走），所以读者手上的
     * 链接继续有效，刷新一下就是新版本。顺序同样是先过乐观锁再写对象。
     */
    @Transactional
    public TreeMutation replacePdf(long id, MultipartFile file, int version, AuthenticatedUser user)
            throws IOException {
        PdfUpload.validate(file);
        DocNodeEntity node = requireNode(id);
        if (node.getNodeType() != DocNodeType.PDF) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "只有 PDF 文档能替换文件");
        }
        String objectKey = pdfObjectKey(node.getPublicId());
        requireUpdated(nodeMapper.updatePdf(id, objectKey, file.getSize(), user.id(), version,
                LocalDateTime.now()));
        storePdf(objectKey, file);
        return new TreeMutation(tree(), id);
    }

    private String pdfObjectKey(String publicId) {
        return "docs/" + publicId + "/document.pdf";
    }

    private void storePdf(String objectKey, MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            storage.put(objectKey, input, file.getSize(), PDF_CONTENT_TYPE);
        }
    }

    /**
     * 正文插图。必须挂在一篇已存在的文档上——图片的公开可见性完全跟随所属文档的
     * 发布状态，没有归属就无从判定，那样的图片只能一律拒绝，等于上传了个死链。
     */
    @Transactional
    public AssetUploaded uploadAsset(long nodeId, MultipartFile file, AuthenticatedUser user)
            throws IOException {
        DocNodeEntity node = requireNode(nodeId);
        if (node.getNodeType() != DocNodeType.DOCUMENT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "只能给文档上传插图");
        }
        byte[] bytes = InlineImageUpload.read(file);
        String contentType = file.getContentType();
        String id = PublicId.next();
        String objectKey = "docs/assets/" + id + "." + InlineImageUpload.extension(contentType);
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
        DocAssetEntity asset = new DocAssetEntity();
        asset.setId(id);
        asset.setNodeId(nodeId);
        asset.setObjectKey(objectKey);
        asset.setContentType(contentType);
        asset.setSize((long) bytes.length);
        asset.setUploadedBy(user.id());
        asset.setCreatedAt(LocalDateTime.now());
        try {
            assetMapper.insert(asset);
        } catch (RuntimeException failure) {
            storage.delete(objectKey);
            throw failure;
        }
        return new AssetUploaded(id, ASSET_URL_PREFIX + id);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private DocNodeEntity requireNode(long id) {
        DocNodeEntity node = nodeMapper.selectById(id);
        if (node == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在");
        return node;
    }

    private void requireFolder(DocNodeEntity node) {
        if (node.getNodeType() != DocNodeType.FOLDER) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "只有文件夹里可以放内容");
        }
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "文档已被其他操作修改，请刷新后重试");
        }
    }

    private String normalizeTitle(String title) {
        String cleaned = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "名称不能为空");
        if (cleaned.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "名称不能超过 200 个字符");
        }
        return cleaned;
    }

    private void requireUniqueTitle(Long parentId, String title, DocNodeType nodeType, Long excludeId) {
        if (nodeMapper.countSiblingTitle(parentId, title, nodeType.isFolder(), excludeId) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "同一目录下已经有同名的" + (nodeType.isFolder() ? "文件夹" : "文档"));
        }
    }

    private void renumber(List<Long> orderedIds, LocalDateTime now) {
        for (int cursor = 0; cursor < orderedIds.size(); cursor++) {
            nodeMapper.updateSortOrder(orderedIds.get(cursor), cursor, now);
        }
    }

    /** 按父节点分组；根节点挂在 null 键上（HashMap 允许 null 键）。 */
    private Map<Long, List<DocNodeEntity>> byParent(List<DocNodeEntity> nodes) {
        Map<Long, List<DocNodeEntity>> grouped = new HashMap<>();
        for (DocNodeEntity node : nodes) {
            grouped.computeIfAbsent(node.getParentId(), key -> new ArrayList<>()).add(node);
        }
        return grouped;
    }

    private List<ManageNode> buildManage(Map<Long, List<DocNodeEntity>> children, Long parentId, int depth) {
        // 深度兜底。移动接口已经挡住了环，但树遍历是渲染路径上唯一会因脏数据
        // 无限递归的地方，多一道保险比事后查栈溢出便宜。
        if (depth > MAX_DEPTH) return List.of();
        List<ManageNode> result = new ArrayList<>();
        for (DocNodeEntity node : children.getOrDefault(parentId, List.of())) {
            result.add(toManage(node, buildManage(children, node.getId(), depth + 1)));
        }
        return result;
    }

    /**
     * 读者目录。空文件夹会被整个剪掉——一个文件夹只有在子树里确实留下了
     * 至少一篇这位读者能看的文档时才出现，否则未登录的人会看到一排点开
     * 全是空的文件夹。
     */
    private List<ReaderNode> buildReader(Map<Long, List<DocNodeEntity>> children, Long parentId,
                                         int depth, boolean authenticated) {
        if (depth > MAX_DEPTH) return List.of();
        List<ReaderNode> result = new ArrayList<>();
        for (DocNodeEntity node : children.getOrDefault(parentId, List.of())) {
            if (!node.getNodeType().isFolder()) {
                if (Boolean.TRUE.equals(node.getPublished()) && visibleTo(node, authenticated)) {
                    result.add(toReader(node, List.of()));
                }
                continue;
            }
            List<ReaderNode> nested = buildReader(children, node.getId(), depth + 1, authenticated);
            if (!nested.isEmpty()) result.add(toReader(node, nested));
        }
        return result;
    }

    /**
     * 可见性判定的唯一实现。目录、正文、插图三条读取路径都必须过这一关，
     * 谁绕过去，谁就成了那道被漏掉的门。
     */
    private boolean visibleTo(DocNodeEntity node, boolean authenticated) {
        return authenticated || !requiresLogin(node);
    }

    /** 缺省按需要登录处理：可见性列缺失只可能是脏数据，此时宁可少给人看。 */
    private boolean requiresLogin(DocNodeEntity node) {
        return node.getVisibility() != DocVisibility.PUBLIC;
    }

    private boolean isDescendant(Map<Long, List<DocNodeEntity>> children, long ancestorId, long candidateId) {
        return descendants(children, ancestorId).contains(candidateId);
    }

    /** 广度优先展开子孙 id。用显式队列而不是递归，脏数据造成的环只会让集合收敛，不会爆栈。 */
    private List<Long> descendants(Map<Long, List<DocNodeEntity>> children, long rootId) {
        List<Long> found = new ArrayList<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.add(rootId);
        while (!pending.isEmpty()) {
            Long current = pending.poll();
            for (DocNodeEntity child : children.getOrDefault(current, List.of())) {
                if (found.contains(child.getId())) continue;
                found.add(child.getId());
                pending.add(child.getId());
            }
        }
        return found;
    }

    /** 子树高度：叶子为 1。移动时用来判断"接过去之后会不会超过层数上限"。 */
    private int heightOf(Map<Long, List<DocNodeEntity>> children, long rootId) {
        int height = 1;
        for (DocNodeEntity child : children.getOrDefault(rootId, List.of())) {
            height = Math.max(height, 1 + heightOf(children, child.getId()));
            if (height > MAX_DEPTH) return height;
        }
        return height;
    }

    /** 节点所在层级，根节点为 1。逐级向上查，层数上限保证了查询次数是常数级。 */
    private int depthOf(DocNodeEntity node) {
        int depth = 1;
        Long parentId = node.getParentId();
        while (parentId != null && depth <= MAX_DEPTH) {
            DocNodeEntity parent = nodeMapper.selectById(parentId);
            if (parent == null) break;
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    /** 从根到该节点（含自身）的名称链，供面包屑显示。 */
    private List<String> breadcrumb(DocNodeEntity node) {
        List<String> titles = new ArrayList<>();
        titles.add(node.getTitle());
        Long parentId = node.getParentId();
        int guard = 0;
        while (parentId != null && guard++ < MAX_DEPTH) {
            DocNodeEntity parent = nodeMapper.selectById(parentId);
            if (parent == null) break;
            titles.add(parent.getTitle());
            parentId = parent.getParentId();
        }
        Collections.reverse(titles);
        return titles;
    }

    /**
     * 读正文。对象缺失或不可读时返回空串并告警，不抛异常——数据库回滚、
     * 桶迁移这类运维事故不应该把公开文档站变成一片 500。
     */
    private String readContent(DocNodeEntity node) {
        // PDF 的 object_key 指向二进制文件，绝不能按 UTF-8 读出来当正文。
        if (node.getNodeType() != DocNodeType.DOCUMENT) return "";
        if (!StringUtils.hasText(node.getObjectKey())) return "";
        try (InputStream input = storage.open(node.getObjectKey())) {
            return new String(input.readNBytes(MAX_CONTENT_BYTES), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            log.warn("文档正文读取失败，按空正文渲染（nodeId={}, objectKey={}）",
                    node.getId(), node.getObjectKey(), failure);
            return "";
        }
    }

    private ManageNode toManage(DocNodeEntity node, List<ManageNode> children) {
        return new ManageNode(node.getId(), node.getPublicId(), node.getParentId(),
                node.getNodeType(), node.getTitle(),
                node.getSortOrder() == null ? 0 : node.getSortOrder(),
                Boolean.TRUE.equals(node.getPublished()),
                node.getVisibility() == null ? DocVisibility.MEMBERS : node.getVisibility(),
                StringUtils.hasText(node.getObjectKey()), node.getContentSize(), node.getSummary(),
                node.getUpdaterDisplayName(), node.getUpdatedAt(),
                node.getVersion() == null ? 1 : node.getVersion(), children);
    }

    private ReaderNode toReader(DocNodeEntity node, List<ReaderNode> children) {
        return new ReaderNode(node.getPublicId(), node.getNodeType(), node.getTitle(),
                node.getSummary(), requiresLogin(node), node.getUpdatedAt(), children);
    }

    private String fileUrl(DocNodeEntity node) {
        return FILE_URL_PREFIX + node.getPublicId() + FILE_URL_SUFFIX;
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    public record ManageNode(long id, String publicId, Long parentId, DocNodeType nodeType,
                             String title, int sortOrder, boolean published,
                             DocVisibility visibility, boolean hasContent,
                             Long contentSize, String summary, String updaterDisplayName,
                             LocalDateTime updatedAt, int version, List<ManageNode> children) {
    }

    /** 写操作统一返回整棵新树：前端本来就要重画目录，一次往返比逐节点打补丁更不容易出错。 */
    public record TreeMutation(List<ManageNode> tree, Long focusId) {
    }

    public record DocumentDetail(ManageNode node, String content, List<String> breadcrumb) {
    }

    /**
     * 读者目录里的一个节点。{@code requiresLogin} 只对已登录的读者有意义——
     * 未登录时需要登录的文档根本不会出现在树里，所以这个字段永远是 false。
     */
    public record ReaderNode(String publicId, DocNodeType nodeType, String title, String summary,
                             boolean requiresLogin, LocalDateTime updatedAt,
                             List<ReaderNode> children) {
    }

    /**
     * 一篇打开的文档。两种叶子共用这一个视图：Markdown 文档带 {@code content}，
     * PDF 文档带 {@code fileUrl}（前端要带令牌取 Blob，不能直接塞给 iframe），
     * 另一个字段为空。刻意不拆成两个接口——拆开就有两处可见性判定。
     */
    public record ReaderDocument(String publicId, DocNodeType nodeType, String title,
                                 String content, String fileUrl, Long fileSize,
                                 boolean requiresLogin, LocalDateTime updatedAt,
                                 String updaterDisplayName, List<String> breadcrumb) {
    }

    public record AssetUploaded(String id, String url) {
    }
}
