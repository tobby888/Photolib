package cn.photolib.storage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class GalleryStorageInterceptor implements HandlerInterceptor {
    private final PhotoStorageReconciliationService reconciliation;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        reconciliation.reconcile();
        return true;
    }
}
