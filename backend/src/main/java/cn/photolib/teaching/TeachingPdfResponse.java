package cn.photolib.teaching;

import cn.photolib.teaching.model.TeachingMaterialEntity;
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
 * 教学资料 PDF 回帖时的响应头，预览与下载共用一个构造点，只在 Content-Disposition 上分叉：
 * inline 给浏览器内置阅读器打开，attachment 直接落盘（下载接口同时计数）。
 */
final class TeachingPdfResponse {
    private TeachingPdfResponse() {
    }

    static ResponseEntity<InputStreamResource> of(TeachingMaterialEntity material, InputStream content,
                                                  boolean download) {
        ContentDisposition disposition = (download ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename(material.getTitle() + ".pdf", StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                // 缓存必须是 private：同一个 URL 只对已登录的图库成员有效，共享缓存会串味。
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF);
        // content_size 是上传时记下的元数据；对对象被换而元数据没跟上时，宁可少写 Content-Length。
        Long size = material.getContentSize();
        if (size != null && size > 0) {
            response.contentLength(size);
        }
        return response.body(new InputStreamResource(content));
    }
}
