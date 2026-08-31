package cn.photolib.doc;

import cn.photolib.doc.model.DocNodeEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * PDF 文档回吐时的响应头，编辑预览和读者直链共用一份。
 *
 * <p>共用是有意的：两条路径的授权判定各自独立（一个要 {@code DOC_MANAGE}，
 * 一个按发布状态和可见范围判），但"回给浏览器的是什么"必须一模一样，
 * 否则同一份文件在两个入口里的文件名、内联行为会莫名其妙地不同。</p>
 */
final class DocPdfResponse {
    private DocPdfResponse() {
    }

    static ResponseEntity<InputStreamResource> of(DocNodeEntity node, InputStream content) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                // 缓存必须是 private：同一个 URL 对匿名访客可能是 403、对成员是文件，
                // 共享缓存会把成员拿到的响应发给下一个匿名访客。
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                // inline 而不是 attachment：浏览器内置阅读器直接打开，想存到本地
                // 仍然可以从阅读器里另存。文件名带中文，必须走 RFC 5987 编码。
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(node.getTitle() + ".pdf", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.APPLICATION_PDF);
        // content_size 是上传时记下的元数据；对象被换掉而元数据没跟上时宁可不写
        // Content-Length，也不要写一个和实际字节数对不上的值——那会让响应被截断。
        Long size = node.getContentSize();
        if (size != null && size > 0) {
            response.contentLength(size);
        }
        return response.body(new InputStreamResource(content));
    }
}
