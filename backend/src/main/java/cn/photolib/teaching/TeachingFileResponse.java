package cn.photolib.teaching;

import cn.photolib.teaching.model.TeachingMaterialEntity;
import cn.photolib.teaching.model.TeachingMaterialFormat;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 教学资料预览时的响应头：只有 PDF 做 inline 预览，Word/PPT 一律 attachment。
 * Content-Type 与文件名的扩展名按格式。下载不走这里，走签名地址。
 */
final class TeachingFileResponse {
    private TeachingFileResponse() {
    }

    static ResponseEntity<InputStreamResource> of(TeachingMaterialEntity material, InputStream content) {
        TeachingMaterialFormat format = material.getFormat() == null
                ? TeachingMaterialFormat.PDF : material.getFormat();
        ContentDisposition disposition = (format.previewable() ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(material.getTitle() + "." + format.extension(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                // 缓存必须是 private：同一个 URL 只对已登录的图库成员有效，共享缓存会串味。
                // 还必须是 no-cache：换文件是原地换、URL 不变，允许直接用缓存的话，
                // 读者刷新后仍会拿到旧文件。
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(format.contentType()));
        // content_size 是上传时记下的元数据；对对象被换而元数据没跟上时，宁可少写 Content-Length。
        Long size = material.getContentSize();
        if (size != null && size > 0) {
            response.contentLength(size);
        }
        return response.body(new InputStreamResource(content));
    }
}
