package cn.photolib.teaching;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.teaching.model.TeachingMaterialEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 教学资料的读取接口，受众是图库成员（持有 {@code PHOTO_VIEW}）。
 *
 * <p>路径挂在 {@code /api/v1/teaching/materials} 下，与管理端点（{@code /api/v1/teaching}）
 * 分开：审计拦截器按"第 3 段资源类型、第 4 段资源 id"归档，所以管理端点的 id 必须紧跟在
 * {@code teaching} 后面，读端点就让出这一段。</p>
 *
 * <p>这里没有匿名面：教学资料和图片库一样，必须登录后、且能进图库才看得到。
 * 预览与下载分成两个地址，只有下载计数——预览读的是同一个对象，
 * 把它也计进去会让"下载次数"变成一个没人能解释的数字。</p>
 */
@RestController
@RequestMapping("/teaching/materials")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PHOTO_VIEW')")
public class TeachingReaderController {
    private final TeachingService service;

    @GetMapping
    ApiResponse<List<TeachingService.Material>> list(@RequestParam(required = false) String category,
                                                    @RequestParam(required = false) String q) {
        return ApiResponse.ok(service.list(category, q));
    }

    @GetMapping("/categories")
    ApiResponse<List<String>> categories() {
        return ApiResponse.ok(service.categories());
    }

    @GetMapping("/{publicId}")
    ApiResponse<TeachingService.Material> get(@PathVariable String publicId) {
        return ApiResponse.ok(service.get(publicId));
    }

    /** 在线预览：只有 PDF inline；Word/PPT 前端不调这个地址，只会走 /download。 */
    @GetMapping("/{publicId}/file")
    ResponseEntity<InputStreamResource> file(@PathVariable String publicId) {
        TeachingMaterialEntity material = service.requireReadable(publicId);
        return TeachingFileResponse.of(material, service.open(material));
    }

    /**
     * 下载：计数 +1 并返回签名地址，浏览器直接去存储取文件。
     * 用 POST 而不是 GET：计数是写操作，要进审计，也不能被浏览器缓存吞掉。
     */
    @PostMapping("/{publicId}/download")
    ApiResponse<TeachingService.Download> download(@PathVariable String publicId,
                                                   HttpServletRequest servletRequest) {
        // 路径第 4 段是 materials，审计拦截器取不到资源 id，这里补上。
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("publicId", publicId));
        return ApiResponse.ok(service.download(publicId));
    }
}
