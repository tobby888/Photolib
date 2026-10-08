package cn.photolib.doc;

import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 挑选"指定读者"名单用的候选项：全部权限组和全部启用中的账号。
 *
 * <p>只开给需要挑名单的人——能设置文档读者范围的（DOC_PUBLISH）和能给文件指定下载范围的
 * （FILE_UPLOAD / FILE_MANAGE）。名单本身在保存时由 {@link DocAudience#normalize} 再校验一遍。</p>
 */
@RestController
@RequestMapping("/doc-audience")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('DOC_PUBLISH','FILE_UPLOAD','FILE_MANAGE')")
public class DocAudienceController {
    private final DocAudience audience;

    @GetMapping
    ApiResponse<DocAudience.Options> options() {
        return ApiResponse.ok(audience.options());
    }
}
