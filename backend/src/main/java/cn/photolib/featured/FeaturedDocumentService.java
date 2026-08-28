package cn.photolib.featured;

import cn.photolib.featured.model.FeaturedCollectionEntity;
import cn.photolib.featured.model.FeaturedDocumentStatus;
import cn.photolib.featured.model.FeaturedEntryEntity;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 精选 Word 文档生成。
 *
 * <p>触发点只有一个：{@link FeaturedCollectionService.FeaturedDocumentRequested}，
 * 且监听器绑定在事务提交之后。提交前生成会读不到刚写入的关闭状态与条目。</p>
 *
 * <p><b>单张图片的问题绝不能让整份文档失败。</b>图片可能在填报之后被删除、
 * 预览对象可能暂时缺失、极少数原图无法被 POI 接受。这些情况一律降级成
 * "这一条只有文字"，并在文档里注明图片不可用——条目的拍摄人、拍摄时间、
 * 拍摄地点和拍摄思路都是提交时的快照，文字内容并不会因此丢失。
 * 只有整体性的失败（写出 docx、上传对象存储）才把文档标记成 FAILED。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeaturedDocumentService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 正文可用宽度，A4 默认页边距下约 6.5 英寸。 */
    private static final int MAX_IMAGE_WIDTH_POINTS = 430;
    private static final int MAX_IMAGE_HEIGHT_POINTS = 520;
    /** 单张配图最多读取的字节数，挡住异常大的对象把生成任务撑爆。 */
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;
    /** 缺少宽高元数据时按 3:2 估算，仅用于排版。 */
    private static final double DEFAULT_ASPECT_RATIO = 2.0 / 3.0;

    private final FeaturedCollectionService collections;
    private final PhotoService photoService;
    private final ObjectStorageService storage;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentRequested(FeaturedCollectionService.FeaturedDocumentRequested event) {
        generate(event.collectionId());
    }

    /** 生成并上传文档，把结果写回精选行。异常不外抛，只落到 document_error。 */
    public void generate(long collectionId) {
        FeaturedCollectionEntity collection = collections.documentSource(collectionId);
        if (collection == null) {
            log.warn("好图精选已不存在，跳过文档生成 collectionId={}", collectionId);
            return;
        }
        try {
            byte[] content = render(collection, collections.entriesForDocument(collectionId));
            String objectKey = "featured/" + collectionId + "/"
                    + FILE_STAMP.format(LocalDateTime.now()) + "-" + System.currentTimeMillis() + ".docx";
            storage.put(objectKey, new ByteArrayInputStream(content), content.length,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            if (!collections.finishDocument(collectionId, FeaturedDocumentStatus.READY, objectKey,
                    (long) content.length, null)) {
                // 另一次生成已经先写回了结果。删掉这次多余的对象，不要留孤儿。
                log.info("好图精选文档结果已被其他任务写回，清理本次对象 collectionId={}", collectionId);
                deleteQuietly(objectKey);
            }
        } catch (Exception failure) {
            log.error("好图精选文档生成失败 collectionId={}", collectionId, failure);
            collections.finishDocument(collectionId, FeaturedDocumentStatus.FAILED, null, null,
                    truncate(failure.getMessage()));
        }
    }

    byte[] render(FeaturedCollectionEntity collection, List<FeaturedEntryEntity> entries) throws Exception {
        Map<Long, PhotoEntity> photos = collections
                .photosForDocument(entries.stream().map(FeaturedEntryEntity::getPhotoId).distinct().toList())
                .stream().collect(Collectors.toMap(PhotoEntity::getId, Function.identity(),
                        (left, right) -> left));
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeCover(document, collection, entries.size());
            if (entries.isEmpty()) {
                paragraph(document, "本次精选没有收到任何投稿。", false, ParagraphAlignment.LEFT);
            }
            // 条目已按"校区编码、填报顺序"排好序（见 FeaturedEntryMapper#findByCollection），
            // 这里只负责在校区变化时开新章节，不要再排一次。
            for (Map.Entry<String, List<FeaturedEntryEntity>> chapter : groupByCampus(entries).entrySet()) {
                writeChapter(document, chapter.getKey(), chapter.getValue(), photos);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 章节键用校区名；未分配校区的条目集中在最后一章。 */
    private Map<String, List<FeaturedEntryEntity>> groupByCampus(List<FeaturedEntryEntity> entries) {
        Map<String, List<FeaturedEntryEntity>> chapters = new LinkedHashMap<>();
        for (FeaturedEntryEntity entry : entries) {
            String campus = entry.getCampusName() == null || entry.getCampusName().isBlank()
                    ? "未分配校区" : entry.getCampusName();
            chapters.computeIfAbsent(campus, key -> new ArrayList<>()).add(entry);
        }
        return chapters;
    }

    private void writeCover(XWPFDocument document, FeaturedCollectionEntity collection, int entryCount) {
        XWPFParagraph title = document.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = title.createRun();
        titleRun.setText(collection.getTitle());
        titleRun.setBold(true);
        titleRun.setFontSize(22);
        applyFont(titleRun);

        paragraph(document, "征集时间：" + DATE_TIME.format(collection.getStartsAt())
                + " 至 " + DATE_TIME.format(collection.getEndsAt()), false, ParagraphAlignment.CENTER);
        paragraph(document, "入选图片：" + entryCount + " 张　　生成时间："
                + DATE_TIME.format(LocalDateTime.now()), false, ParagraphAlignment.CENTER);

        String requirement = collection.getRequirementText();
        if (requirement != null && !requirement.isBlank()) {
            heading(document, "征集要求", 1);
            // 要求正文取纯文本投影：富文本里的图片是给页面看的排版元素，
            // 混进成品文档只会干扰按校区分章的正文结构。
            requirement.lines().filter(line -> !line.isBlank())
                    .forEach(line -> paragraph(document, line.strip(), false, ParagraphAlignment.LEFT));
        }
    }

    private void writeChapter(XWPFDocument document, String campusName,
                              List<FeaturedEntryEntity> entries, Map<Long, PhotoEntity> photos) {
        heading(document, campusName, 1);
        int index = 1;
        for (FeaturedEntryEntity entry : entries) {
            heading(document, index++ + "、" + displayTitle(entry), 2);
            writePicture(document, entry, photos.get(entry.getPhotoId()));
            labelled(document, "拍摄人", blankToDash(entry.getPhotographerName())
                    + (entry.getPhotographerStudentId() == null || entry.getPhotographerStudentId().isBlank()
                    ? "" : "（" + entry.getPhotographerStudentId() + "）"));
            labelled(document, "拍摄时间", entry.getTakenAt() == null ? "—"
                    : DATE_TIME.format(entry.getTakenAt()));
            labelled(document, "拍摄地点", blankToDash(entry.getLocation()));
            labelled(document, "拍摄思路", blankToDash(entry.getIdea()));
            labelled(document, "填报人", blankToDash(entry.getSubmitterDisplayName()));
        }
    }

    private void writePicture(XWPFDocument document, FeaturedEntryEntity entry, PhotoEntity photo) {
        String note = null;
        if (photo == null) {
            note = "（图片已从图库中删除，仅保留文字记录）";
        } else {
            String objectKey = photoService.renderableObjectKey(photo);
            boolean displayable = photo.getStatus() == PhotoStatus.AVAILABLE
                    || photo.getStatus() == PhotoStatus.ARCHIVED;
            if (objectKey == null || !displayable) {
                note = "（图片暂不可用，仅保留文字记录）";
            } else {
                try {
                    byte[] bytes = readObject(objectKey);
                    XWPFParagraph paragraph = document.createParagraph();
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun run = paragraph.createRun();
                    int[] size = fit(photo);
                    run.addPicture(new ByteArrayInputStream(bytes), pictureType(bytes),
                            "photo-" + photo.getId(), Units.toEMU(size[0]), Units.toEMU(size[1]));
                } catch (Exception failure) {
                    // 单张图片读不出来时降级成文字，绝不让整份文档失败。
                    log.warn("好图精选配图写入失败，改为文字条目 entryId={} photoId={}",
                            entry.getId(), photo.getId(), failure);
                    note = "（图片读取失败，仅保留文字记录）";
                }
            }
        }
        if (note != null) paragraph(document, note, true, ParagraphAlignment.CENTER);
    }

    private byte[] readObject(String objectKey) throws Exception {
        try (InputStream input = storage.open(objectKey);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(chunk)) >= 0) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("配图超过 " + MAX_IMAGE_BYTES + " 字节上限");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * 图片格式只按字节魔数判定。{@code photo.content_type} 描述的是来源且不可信
     * （旧库迁移会把老系统的 mime_type 原样搬过来），用它推导会写出格式声明与
     * 实际字节不符的 docx。
     */
    private int pictureType(byte[] bytes) {
        boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G';
        return png ? Document.PICTURE_TYPE_PNG : Document.PICTURE_TYPE_JPEG;
    }

    /** 等比缩放到正文可用区域内。缺少宽高元数据时按 3:2 估算。 */
    private int[] fit(PhotoEntity photo) {
        double ratio = photo.getWidth() != null && photo.getHeight() != null
                && photo.getWidth() > 0 && photo.getHeight() > 0
                ? (double) photo.getHeight() / photo.getWidth()
                : DEFAULT_ASPECT_RATIO;
        int width = MAX_IMAGE_WIDTH_POINTS;
        int height = (int) Math.round(width * ratio);
        if (height > MAX_IMAGE_HEIGHT_POINTS) {
            height = MAX_IMAGE_HEIGHT_POINTS;
            width = (int) Math.round(height / ratio);
        }
        return new int[]{Math.max(1, width), Math.max(1, height)};
    }

    private void heading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(level == 1 ? 16 : 13);
        applyFont(run);
    }

    private void labelled(XWPFDocument document, String label, String value) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun labelRun = paragraph.createRun();
        labelRun.setText(label + "：");
        labelRun.setBold(true);
        applyFont(labelRun);
        // 多行的拍摄思路要保留换行，否则整段会挤成一行。
        String[] lines = value.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            XWPFRun valueRun = paragraph.createRun();
            valueRun.setText(lines[index]);
            applyFont(valueRun);
            if (index < lines.length - 1) valueRun.addBreak();
        }
    }

    private void paragraph(XWPFDocument document, String text, boolean italic, ParagraphAlignment alignment) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setItalic(italic);
        applyFont(run);
    }

    /**
     * 中文字体只声明字体名，不内嵌字体文件：服务器上不一定装了中文字体，
     * 也不能把字体文件打进产物。Word 会在打开文档时按名字回退到本机可用字体。
     */
    private void applyFont(XWPFRun run) {
        run.setFontFamily("宋体");
    }

    private String displayTitle(FeaturedEntryEntity entry) {
        return entry.getPhotoTitle() == null || entry.getPhotoTitle().isBlank()
                ? "未命名作品" : entry.getPhotoTitle();
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private void deleteQuietly(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException failure) {
            log.warn("清理多余的精选文档对象失败 objectKey={}", objectKey, failure);
        }
    }

    private String truncate(String message) {
        if (message == null) return "文档生成失败";
        return message.length() <= 900 ? message : message.substring(0, 900);
    }

    /** 下载文件名。带中文，控制器/签名地址必须按 UTF-8 编码它。 */
    static String fileName(FeaturedCollectionEntity collection) {
        String safeTitle = collection.getTitle().replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        if (safeTitle.isEmpty()) safeTitle = "好图精选";
        if (safeTitle.length() > 60) safeTitle = safeTitle.substring(0, 60);
        return safeTitle + "-好图精选.docx";
    }
}
