package cn.photolib.auth.mfa;

import cn.photolib.permission.PermissionCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "能打开要求再验证的操作的权限，其所在组一律强制两步验证"——这条规则靠
 * {@link PermissionCode#unlocksStepUpOperation()} 里的清单成立。以后给新接口加
 * {@code @RequiresStepUp}、或者改了某个接口的权限码，清单跟不上的话，那个权限就能被交给
 * "不使用两步验证"的组，删除之类的操作又变回不用验证。这里扫描全部接口核对。
 */
@SpringBootTest
class StepUpPermissionCoverageTests {
    private static final Pattern AUTHORITY_CHECK = Pattern.compile("has(?:Any)?Authority\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyPermissionThatUnlocksAGuardedEndpointForcesTwoFactor() {
        List<String> guarded = new ArrayList<>();
        Set<String> unlisted = new LinkedHashSet<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            if (!AnnotatedElementUtils.hasAnnotation(handler.getMethod(), RequiresStepUp.class)
                    && !AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RequiresStepUp.class)) {
                continue;
            }
            guarded.add(handler.getShortLogMessage());
            PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (rule == null) rule = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            if (rule == null) continue;
            Matcher check = AUTHORITY_CHECK.matcher(rule.value());
            while (check.find()) {
                Matcher code = QUOTED.matcher(check.group(1));
                while (code.find()) {
                    if (!PermissionCode.valueOf(code.group(1)).unlocksStepUpOperation()) {
                        unlisted.add(code.group(1) + " <- " + handler.getShortLogMessage());
                    }
                }
            }
        }
        // 防止扫描本身失效（比如注解换了位置）而让这条测试永远空跑通过。
        assertThat(guarded).anyMatch(name -> name.contains("PhotoController"))
                .anyMatch(name -> name.contains("ProjectController"))
                .anyMatch(name -> name.contains("RequestController"));
        assertThat(unlisted).as("这些权限能打开要求再验证的接口，却不会让所在组被强制两步验证").isEmpty();
    }
}
