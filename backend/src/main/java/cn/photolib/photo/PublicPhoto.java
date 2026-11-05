package cn.photolib.photo;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.directory.CampusMemberService;
import cn.photolib.photo.model.PhotoEntity;

/**
 * 公共照片：不属于任何成员、不计被引、上传链接里选了它就不能申报工时的照片。
 *
 * <p><b>它是一个拍摄者，不是图片上的一个开关。</b>照片上存的仍是拍摄者姓名 / 学号快照，公共照片
 * 的拍摄者固定为 {@value #PHOTOGRAPHER_NAME}、学号为空串（列是 NOT NULL）。这样图库「按拍摄者
 * 筛选」里它就是一个普通的作者，在总库里一筛就能找到；也不必在图片上再存一列、维护两份真相。
 * 判定一律走 {@link #isPublic}，不要在别处手写字符串比较。</p>
 *
 * <p>正因为按姓名判定，{@value #PHOTOGRAPHER_NAME} 是保留的拍摄者姓名：上传链接的访客不能把它
 * 当成自己的姓名报上来（否则就能带着学号给"公共照片"申报工时）。</p>
 */
public final class PublicPhoto {
    public static final String PHOTOGRAPHER_NAME = "公共照片";
    public static final String PHOTOGRAPHER_STUDENT_ID = "";
    public static final Photographer PHOTOGRAPHER =
            new Photographer(PHOTOGRAPHER_STUDENT_ID, PHOTOGRAPHER_NAME);

    private PublicPhoto() {
    }

    public static boolean isPublic(String photographerName) {
        return photographerName != null && PHOTOGRAPHER_NAME.equals(photographerName.strip());
    }

    public static boolean isPublic(PhotoEntity photo) {
        return photo != null && isPublic(photo.getPhotographerName());
    }

    /**
     * 站内上传 / 编辑时的拍摄者：选了公共照片就是 {@link #PHOTOGRAPHER}，否则照旧必须来自通讯录
     * （§2.11，校区规则见 {@link CampusMemberService#resolvePhotographer}）。
     */
    public static Photographer resolve(Boolean publicPhoto, Long contactId, Long campusId,
                                       CampusMemberService members) {
        if (Boolean.TRUE.equals(publicPhoto)) return PHOTOGRAPHER;
        var member = members.resolvePhotographer(contactId, campusId);
        return new Photographer(member.getStudentId(), member.getName());
    }

    /** 已被引的图不能改成公共照片：公共照片不计被引，留着那条被引记录就会算到"公共照片"头上。 */
    public static BusinessException adoptedCannotBecomePublic() {
        return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                "图片已有被引记录，不能改成公共照片；请先取消被引");
    }

    /** 拍摄者快照。 */
    public record Photographer(String studentId, String name) {
        public boolean isPublic() {
            return PublicPhoto.isPublic(name);
        }
    }
}
