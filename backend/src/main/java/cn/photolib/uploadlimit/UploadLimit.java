package cn.photolib.uploadlimit;

import cn.photolib.common.upload.ImageUploadPolicy;

/**
 * 管理员可调的上传限额。每一项自带可调范围和内置默认值；实际生效值由
 * {@link UploadLimitService} 从数据库读出（没改过的项用默认值）。
 *
 * <p>范围的上限是系统能力，不是业务偏好：图片解码的内存、对象存储单次 PUT 的上限、
 * multipart 请求体上限、读进堆内存的文件大小。管理员只能在这个范围内调，数据库里
 * 残留的越界值读出时也会被夹回范围内。</p>
 *
 * <p>枚举名就是接口和数据库里用的键，改名等于丢掉管理员已保存的设置。</p>
 */
public enum UploadLimit {
    PHOTO_IMAGE_MAX_BYTES(Group.PHOTO, Unit.BYTES, "单张图片",
            "图库上传、需求交付、选题上传链接里每张 JPG / PNG 原图的大小，ZIP 里的每张图片同样受它约束。",
            Sizes.MIB, 100 * Sizes.MIB, ImageUploadPolicy.MAX_IMAGE_BYTES),
    PHOTO_ZIP_MAX_BYTES(Group.PHOTO, Unit.BYTES, "ZIP 压缩包",
            "批量上传（含选题上传链接）时一个 ZIP 压缩包的大小。上限取自对象存储单次上传的能力。",
            Sizes.MIB, 1_500_000_000L, 5_000_000_000L),
    PHOTO_ZIP_MAX_IMAGES(Group.PHOTO, Unit.COUNT, "ZIP 内图片张数",
            "一个 ZIP 压缩包里最多能有多少张图片，超出时整个压缩包被拒绝。",
            1, 100, 1000),

    RECRUITMENT_IMAGE_MAX_BYTES(Group.RECRUITMENT, Unit.BYTES, "单张图片",
            "公开招募报名时上传的每张作品图片的大小，ZIP 里的每张图片同样受它约束。",
            Sizes.MIB, 20 * Sizes.MIB, ImageUploadPolicy.MAX_IMAGE_BYTES),
    RECRUITMENT_ZIP_MAX_BYTES(Group.RECRUITMENT, Unit.BYTES, "ZIP 压缩包",
            "报名者上传作品 ZIP 压缩包的大小。招募页面不需要登录，放宽前请考虑被滥用的风险。",
            Sizes.MIB, 200 * Sizes.MIB, 1_500_000_000L),
    RECRUITMENT_MAX_IMAGES(Group.RECRUITMENT, Unit.COUNT, "作品图片张数",
            "报名者逐张上传时一次最多选多少张，以及 ZIP 压缩包里最多能有多少张图片。",
            1, 20, 200),

    FORM_FILE_MAX_BYTES(Group.FORM, Unit.BYTES, "「上传文件」题单个文件",
            "招募报名表和问卷里「上传文件」题目的每个文件大小。",
            Sizes.MIB, 50 * Sizes.MIB, Sizes.GIB),

    INLINE_IMAGE_MAX_BYTES(Group.CONTENT, Unit.BYTES, "正文插图",
            "需求和选题描述、站内消息与问题反馈、文档中心正文里插入的图片（JPEG / PNG / WebP）。",
            256 * Sizes.KIB, 5 * Sizes.MIB, 20 * Sizes.MIB),
    DOC_PDF_MAX_BYTES(Group.CONTENT, Unit.BYTES, "文档中心 PDF",
            "文档中心里直接上传或替换的 PDF 文档。",
            Sizes.MIB, 50 * Sizes.MIB, Sizes.GIB),
    TEACHING_PDF_MAX_BYTES(Group.CONTENT, Unit.BYTES, "教学资料 PDF",
            "教学资料里上传或替换的 PDF 文件。",
            Sizes.MIB, 100 * Sizes.MIB, Sizes.GIB),
    TEACHING_WORD_MAX_BYTES(Group.CONTENT, Unit.BYTES, "教学资料 Word",
            "教学资料里上传或替换的 Word（.docx）文件。",
            Sizes.MIB, 20 * Sizes.MIB, Sizes.GIB),
    TEACHING_PPT_MAX_BYTES(Group.CONTENT, Unit.BYTES, "教学资料 PPT",
            "教学资料里上传或替换的 PPT（.pptx）文件。",
            Sizes.MIB, 100 * Sizes.MIB, Sizes.GIB),

    AVATAR_MAX_BYTES(Group.SITE, Unit.BYTES, "个人头像",
            "成员上传的头像图片（裁切前的原图和裁切后的结果都受它约束）。",
            256 * Sizes.KIB, Sizes.MIB, 5 * Sizes.MIB),
    BRAND_ICON_MAX_BYTES(Group.SITE, Unit.BYTES, "站点图标",
            "系统管理里上传的站点图标和定时切换图标，每张的大小。",
            64 * Sizes.KIB, 512 * Sizes.KIB, 2 * Sizes.MIB),
    PLACEHOLDER_IMAGE_MAX_BYTES(Group.SITE, Unit.BYTES, "缺图占位图",
            "页面定制里上传的缺图占位图，每张的大小。",
            256 * Sizes.KIB, 3 * Sizes.MIB, 10 * Sizes.MIB),

    DATABASE_BACKUP_MAX_BYTES(Group.SYSTEM, Unit.BYTES, "数据库备份文件",
            "数据备份里上传导入的备份文件（压缩后）大小。",
            Sizes.MIB, 512 * Sizes.MIB, 1500 * Sizes.MIB);

    private final Group group;
    private final Unit unit;
    private final String label;
    private final String description;
    private final long min;
    private final long defaultValue;
    private final long max;

    UploadLimit(Group group, Unit unit, String label, String description,
                long min, long defaultValue, long max) {
        this.group = group;
        this.unit = unit;
        this.label = label;
        this.description = description;
        this.min = min;
        this.defaultValue = defaultValue;
        this.max = max;
    }

    public Group group() {
        return group;
    }

    public Unit unit() {
        return unit;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 内置的可调下限。 */
    public long min() {
        return min;
    }

    /** 内置默认值；个别项由 {@link UploadLimitService} 按配置文件改写。 */
    public long defaultValue() {
        return defaultValue;
    }

    /** 内置的可调上限；个别项由 {@link UploadLimitService} 按配置文件收紧。 */
    public long max() {
        return max;
    }

    public enum Unit {
        BYTES,
        COUNT
    }

    /** 管理页面上的分组，顺序即显示顺序。 */
    public enum Group {
        PHOTO("图库与交付"),
        RECRUITMENT("公开招募"),
        FORM("招募与问卷的附件"),
        CONTENT("正文与资料"),
        SITE("头像与站点外观"),
        SYSTEM("系统维护");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    static final class Sizes {
        static final long KIB = 1024;
        static final long MIB = 1024 * KIB;
        static final long GIB = 1024 * MIB;

        private Sizes() {
        }
    }
}
