package cn.photolib.doc.file;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.doc.DocRateLimiter;
import cn.photolib.doc.DocReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 文件库的读者接口：列表和下载。
 *
 * <p>和文档的读者接口一样，{@code public} 指的是"不带令牌也能调用"，不是"返回的都是公开内容"：
 * 未登录只拿得到"所有人"那一档，登录后按账号和权限组再放开。不拆成两套接口——两处判定迟早漂移，
 * 漂移的方向恰好是把内部文件漏给匿名访客。</p>
 *
 * <p>下载用 POST：它要计数、扣流量，是写操作，要进审计，也不能被任何缓存吞掉。
 * 路径是 {@code /public/doc-files/{publicId}/download}，审计拦截器对 {@code public} 前缀
 * 自动往后挪一段，资源类型记成 DOC-FILES、资源 id 是 publicId。</p>
 */
@RestController
@RequestMapping("/public/doc-files")
@RequiredArgsConstructor
public class DocFileReaderController {
    private final DocFileService service;
    private final DocRateLimiter rateLimiter;

    @GetMapping
    ApiResponse<PageResponse<DocFileService.FileView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(DocFileService.MAX_PAGE_SIZE) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean mine,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request) {
        // 列表只对匿名限速，理由同文档目录：成员可追责，限住他们只会妨碍正常使用。
        if (user == null) rateLimiter.requireAllowed(DocRateLimiter.Action.PUBLIC_FILE_LIST, request.getRemoteAddr());
        return ApiResponse.ok(service.list(DocReader.of(user), keyword, mine, page, pageSize));
    }

    @PostMapping("/{publicId}/download")
    ApiResponse<DocFileService.Download> download(@PathVariable String publicId,
                                                  @AuthenticationPrincipal AuthenticatedUser user,
                                                  HttpServletRequest request) {
        DocFileService.Download download = service.download(publicId, DocReader.of(user), request.getRemoteAddr());
        request.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of(
                "size", download.size(), "anonymous", user == null));
        return ApiResponse.ok(download);
    }
}
