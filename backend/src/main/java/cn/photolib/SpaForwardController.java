package cn.photolib;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Lets React Router own browser routes while REST endpoints remain under /api/v1.
 *
 * <p>清单必须和 {@code src/App.tsx} 里的 {@code <Route path=...>} 一一对应：前端用的是
 * HashRouter，路径式深链靠 {@code src/deepLink.ts} 在启动时搬进 hash，而那一步只有在
 * 后端先把该路径 forward 到 index.html 时才有机会执行——漏掉一条，直接访问就是 404
 * 而不是应用。新增前端路由时，这里和 {@code SecurityConfig} 的 permitAll 清单要同步补。
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            // 未登录可达的页面
            "/login",
            "/initial-password",
            "/recruitment",
            "/docs",
            "/docs/{publicId}",
            "/share/{token}",
            // 工作台内的页面
            "/projects",
            "/projects/{id}",
            "/requests",
            "/requests/{id}",
            "/photos",
            "/photos/batch-upload",
            "/photos/{photoId}",
            "/favorites",
            "/favorites/{photoId}",
            "/worklogs",
            "/directory",
            "/featured",
            "/featured/{collectionId}",
            "/notifications",
            "/notifications/{id}",
            "/statistics",
            "/manager-campuses",
            "/recruitments",
            "/recruitments/{id}",
            "/recruitments/{id}/applications/{applicationId}",
            "/recruitment-applications/{id}",
            "/documents",
            "/documents/{publicId}",
            "/admin"
    })
    String forwardToApplication() {
        return "forward:/index.html";
    }
}
