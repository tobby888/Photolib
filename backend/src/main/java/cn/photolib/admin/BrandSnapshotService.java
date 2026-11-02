package cn.photolib.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站外场景（邮件）要用的品牌信息：名称、Slogan 和一张位图图标。
 *
 * <p>图标与页面上看到的一致：今天有定时图标就用定时图标，否则看品牌设置——自定义图标原样用，
 * 内置图标用 {@code resources/mail/brand-icons/} 下预先渲染好的 PNG。页面上的内置图标是 SVG 字形，
 * 而大多数邮件客户端不显示 SVG。</p>
 */
@Service
@RequiredArgsConstructor
public class BrandSnapshotService {
    static final String DEFAULT_TITLE = "摄影工作站";
    static final String DEFAULT_SLOGAN = "影像协作平台";
    private static final Set<String> BUILTIN_ICONS = Set.of("camera", "aperture", "picture", "bulb", "star");
    private static final int SETTING_ID = 1;

    private final BrandingSettingMapper mapper;
    private final ScheduledBrandIconService scheduledIcons;
    private final Map<String, byte[]> builtinCache = new ConcurrentHashMap<>();

    public BrandSnapshot current() {
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        String title = setting == null || blank(setting.getTitle()) ? DEFAULT_TITLE : setting.getTitle().trim();
        String slogan = setting == null || setting.getSlogan() == null ? DEFAULT_SLOGAN : setting.getSlogan().trim();
        ScheduledBrandIconEntity active = scheduledIcons.findActive(LocalDate.now(ScheduledBrandIconService.SYSTEM_ZONE));
        if (active != null) {
            ScheduledBrandIconEntity full = scheduledIcons.getIcon(active.getId());
            if (full != null && full.getIcon() != null && full.getIcon().length > 0) {
                return new BrandSnapshot(title, slogan, full.getIcon(), full.getIconContentType());
            }
        }
        if (setting != null && "custom".equals(setting.getIconType()) && setting.getCustomIcon() != null
                && setting.getCustomIcon().length > 0) {
            return new BrandSnapshot(title, slogan, setting.getCustomIcon(), setting.getCustomIconContentType());
        }
        String builtin = setting == null || !BUILTIN_ICONS.contains(setting.getBuiltinIcon())
                ? "camera" : setting.getBuiltinIcon();
        return new BrandSnapshot(title, slogan, builtinIcon(builtin), "image/png");
    }

    private byte[] builtinIcon(String key) {
        return builtinCache.computeIfAbsent(key, name -> {
            try (InputStream in = new ClassPathResource("mail/brand-icons/" + name + ".png").getInputStream()) {
                return in.readAllBytes();
            } catch (IOException ex) {
                throw new UncheckedIOException("缺少内置图标 " + name, ex);
            }
        });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** {@code iconContentType} 只会是 image/png 或 image/jpeg（自定义图标在上传时已统一转码）。 */
    public record BrandSnapshot(String title, String slogan, byte[] icon, String iconContentType) {}
}
