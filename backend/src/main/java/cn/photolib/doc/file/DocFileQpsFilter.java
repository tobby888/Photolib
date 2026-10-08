package cn.photolib.doc.file;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 文件库上传 / 下载的全站 QPS 闸（额度见上传限额里的 FILE_UPLOAD_QPS / FILE_DOWNLOAD_QPS）。
 *
 * <p>放在 Servlet 过滤器而不是控制器里，是因为 multipart 在进控制器之前就被整个读完、落成临时文件了：
 * 在控制器里拒绝一个超额的上传，带宽和磁盘早已花掉。过滤器排在 Spring Security 之后
 * （Spring Boot 给安全过滤器链的顺序更靠前），未登录的上传请求在那里就已经 401，不占这里的额度。</p>
 */
@Component
@RequiredArgsConstructor
public class DocFileQpsFilter extends OncePerRequestFilter {
    private final DocFileTraffic traffic;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod())) {
            String path = request.getRequestURI();
            boolean upload = path.endsWith("/api/v1/doc-files");
            boolean download = path.contains("/api/v1/public/doc-files/") && path.endsWith("/download");
            if ((upload && !traffic.tryAcquireUpload()) || (download && !traffic.tryAcquireDownload())) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\""
                        + (upload ? "上传" : "下载") + "的人太多了，请稍等几秒再试\",\"details\":[]}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
