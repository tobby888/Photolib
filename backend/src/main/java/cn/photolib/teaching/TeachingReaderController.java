package cn.photolib.teaching;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.teaching.model.TeachingMaterialEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教学资料的读取接口，受众是图库成员（持有 {@code PHOTO_VIEW}）。
 *
 * <p>路径挂在 {@code /api/v1/teaching/materials} 下，与管理端点（{@code /api/v1/teaching}）
 * 分开：审计拦截器按"第 3 段资源类型、第 4 段资源 id"归档，所以管理端点的 id 必须紧跟在
 * {@code teaching} 后面，读端点就让出这一段。</p>
 *
 * <p>这里没有匿名面：教学资料和图片库一样，必须登录后、且能进图库才看得到。
 * 预览（inline）与下载（attachment）分成两个地址，只有下载计数——预览读的是同一个对象，
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
        return TeachingFileResponse.of(material, service.open(material), false);
    }

    /** 下载：attachment，计数 +1。 */
    @GetMapping("/{publicId}/download")
    ResponseEntity<InputStreamResource> download(@PathVariable String publicId) {
        TeachingMaterialEntity material = service.requireReadable(publicId);
        service.recordDownload(material.getId());
        return TeachingFileResponse.of(material, service.open(material), true);
    }
}
