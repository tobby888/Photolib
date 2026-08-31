package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.doc.model.DocAssetEntity;
import cn.photolib.doc.model.DocNodeEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 文档中心的阅读接口。
 *
 * <p>路径里的 {@code public} 指的是"这些接口不需要登录就能调用"，
 * <b>不是</b>"返回的内容都是公开的"。同一个接口对两类读者返回不同的结果：
 * 未登录只能拿到 {@code PUBLIC} 文档，登录后连同 {@code MEMBERS} 文档一起返回。
 * 判定在 {@link DocService} 里完成，这里只负责把"当前调用方是否已登录"传下去。</p>
 *
 * <p>之所以不拆成"匿名接口 + 登录接口"两套：拆开就有两处判定，
 * 两处判定迟早会漂移，而漂移的方向恰好是把内部文档漏给匿名访客。</p>
 *
 * <p>Spring Security 对这些路径是 permitAll，但 {@code AccessTokenFilter}
 * 仍然会跑：带着有效令牌来的请求，principal 就不是 null。这正是本控制器
 * 判断读者身份的依据。</p>
 */
@RestController
@RequestMapping("/public/docs")
@RequiredArgsConstructor
public class DocReaderController {
    private final DocService service;
    private final DocRateLimiter rateLimiter;

    @GetMapping
    ApiResponse<List<DocService.ReaderNode>> tree(@AuthenticationPrincipal AuthenticatedUser user,
                                                  HttpServletRequest request) {
        limit(DocRateLimiter.Action.PUBLIC_TREE, user, request);
        return ApiResponse.ok(service.readerTree(user != null));
    }

    @GetMapping("/assets/{assetId}")
    ResponseEntity<InputStreamResource> asset(@PathVariable String assetId,
                                              @AuthenticationPrincipal AuthenticatedUser user,
                                              HttpServletRequest request) {
        limit(DocRateLimiter.Action.PUBLIC_ASSET, user, request);
        DocAssetEntity asset = service.readerAsset(assetId, user != null);
        // 缓存必须是 private：同一个 URL 对匿名访客可能是 403、对成员是图片，
        // 共享缓存会把成员拿到的响应发给下一个匿名访客。
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .contentLength(asset.getSize())
                .body(new InputStreamResource(service.openAsset(asset)));
    }

    /**
     * PDF 文档本身。可见性判定和正文接口是同一套（{@code DocService.readerPdf}）：
     * 这个直链就是 PDF 文档的正文，判松一格等于把仅限成员的文件放上公网。
     * 缓存同样必须是 private——同一个 URL 对匿名访客可能是 403、对成员是文件。
     */
    @GetMapping("/{publicId}/file")
    ResponseEntity<InputStreamResource> file(@PathVariable String publicId,
                                             @AuthenticationPrincipal AuthenticatedUser user,
                                             HttpServletRequest request) {
        limit(DocRateLimiter.Action.PUBLIC_FILE, user, request);
        DocNodeEntity node = service.readerPdf(publicId, user != null);
        return DocPdfResponse.of(node, service.openNode(node));
    }

    @GetMapping("/{publicId}")
    ApiResponse<DocService.ReaderDocument> document(@PathVariable String publicId,
                                                    @AuthenticationPrincipal AuthenticatedUser user,
                                                    HttpServletRequest request) {
        limit(DocRateLimiter.Action.PUBLIC_DOCUMENT, user, request);
        return ApiResponse.ok(service.readerDocument(publicId, user != null));
    }

    /**
     * 登录用户不限速：限速是为了挡住匿名爬取，而成员是可追责的，
     * 把他们一起限住只会妨碍正常阅读。
     */
    private void limit(DocRateLimiter.Action action, AuthenticatedUser user,
                       HttpServletRequest request) {
        if (user != null) return;
        rateLimiter.requireAllowed(action, request.getRemoteAddr());
    }
}
