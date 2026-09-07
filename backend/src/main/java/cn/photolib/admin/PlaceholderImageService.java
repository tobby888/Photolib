package cn.photolib.admin;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 缺图占位图。前端在图片没有预览地址、预览地址加载失败或图片已被软删除时，
 * 从这里配置的若干张图片中任取一张顶上，而不是画一个灰底方块。
 * 一张都没配时前端仍退回内置的灰底占位，所以这个功能可以随时清空。
 */
@Service
@RequiredArgsConstructor
public class PlaceholderImageService {
    static final int MAX_IMAGES = 12;

    private final PlaceholderImageMapper mapper;
    private final BrandIconValidator imageValidator;

    List<PlaceholderImageView> list() {
        return metadata().stream().map(this::toView).toList();
    }

    /** 挂在 `GET /branding` 上给所有页面用：只是一串地址，不含图片字节。 */
    List<String> imageUrls() {
        return metadata().stream().map(this::url).toList();
    }

    PlaceholderImageEntity getImage(long id) {
        return mapper.selectById(id);
    }

    @Transactional
    public List<PlaceholderImageView> add(List<MultipartFile> files) throws IOException {
        List<MultipartFile> uploaded = files == null ? List.of() : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (uploaded.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择要上传的占位图");
        }
        long existing = mapper.selectCount(null);
        if (existing + uploaded.size() > MAX_IMAGES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "占位图最多保存 " + MAX_IMAGES + " 张，当前已有 " + existing + " 张");
        }
        List<PlaceholderImageEntity> prepared = new ArrayList<>();
        for (MultipartFile file : uploaded) {
            BrandIconValidator.NormalizedIcon normalized = imageValidator.normalizePlaceholder(file);
            PlaceholderImageEntity entity = new PlaceholderImageEntity();
            entity.setFileName(fileName(file));
            entity.setImage(normalized.bytes());
            entity.setImageContentType(normalized.contentType());
            prepared.add(entity);
        }
        // 所有文件都校验通过之后才落库：一批里有一张不合格时，不该留下半批。
        prepared.forEach(mapper::insert);
        return list();
    }

    @Transactional
    public List<PlaceholderImageView> remove(long id) {
        if (mapper.deleteById(id) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "占位图不存在或已被删除");
        }
        return list();
    }

    private String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        // 只留文件名本身：上传方给的路径既没用，又会把目录结构写进库里。
        String name = original == null || original.isBlank() ? "placeholder"
                : original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        if (name.isBlank()) name = "placeholder";
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private List<PlaceholderImageEntity> metadata() {
        return mapper.selectList(Wrappers.<PlaceholderImageEntity>lambdaQuery()
                .select(PlaceholderImageEntity::getId, PlaceholderImageEntity::getFileName,
                        PlaceholderImageEntity::getImageContentType, PlaceholderImageEntity::getUpdatedAt)
                .orderByAsc(PlaceholderImageEntity::getId));
    }

    private String url(PlaceholderImageEntity entity) {
        LocalDateTime updatedAt = entity.getUpdatedAt();
        String version = updatedAt == null ? "0" : String.valueOf(updatedAt.hashCode());
        return "/api/v1/branding/placeholder-images/" + entity.getId() + "/image?v=" + version;
    }

    private PlaceholderImageView toView(PlaceholderImageEntity entity) {
        return new PlaceholderImageView(String.valueOf(entity.getId()), entity.getFileName(), url(entity));
    }

    public record PlaceholderImageView(String id, String fileName, String imageUrl) {
    }
}
