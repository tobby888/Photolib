package cn.photolib;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Lets React Router own browser routes while REST endpoints remain under /api/v1.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/login",
            "/initial-password",
            "/projects",
            "/projects/{id}",
            "/requests",
            "/requests/{id}",
            "/photos",
            "/worklogs",
            "/notifications",
            "/notifications/{id}",
            "/statistics",
            "/admin",
            "/recruitment",
            "/recruitments",
            "/recruitments/{id}",
            "/recruitments/{id}/applications/{applicationId}",
            "/recruitment-applications/{id}",
            "/docs",
            "/docs/{publicId}",
            "/documents",
            "/documents/{publicId}"
    })
    String forwardToApplication() {
        return "forward:/index.html";
    }
}
