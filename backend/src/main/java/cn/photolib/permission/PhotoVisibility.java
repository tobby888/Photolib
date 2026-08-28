package cn.photolib.permission;

/**
 * 权限组的图库可见范围：决定账号在图库（以及项目/需求相册的图片列表、图片详情、下载）
 * 里能看到哪些人的图片。与 {@link DataScope} 正交——数据范围管的是校区授权本身，
 * 这里管的是"看得到的图片属于谁"。
 *
 * <p>只影响"看"和"下载"。编辑、归档、删除、标记被引这些写操作仍然是"仅限本人上传"，
 * 放宽可见范围不会连带放开它们（见 {@code PhotoService} 的 {@code requireCanManageMetadata}、
 * {@code validateDelete}、{@code changeArchive}）。</p>
 *
 * <p>好图精选选图也不看这个设置：无论可见范围多宽，负责人只能选授权校区内的图片
 * （见 {@code PhotoService.requireGallerySelectable}）。</p>
 */
public enum PhotoVisibility {
    /** 仅本人上传的图片。校区范围账号的历史默认值。 */
    SELF("仅本人上传"),
    /** 授权校区内所有人上传的图片；全局数据范围下等同于全站。 */
    CAMPUS("授权校区内全部"),
    /** 全站图片，不受校区授权限制。 */
    GLOBAL("全站全部");

    private final String label;

    PhotoVisibility(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
