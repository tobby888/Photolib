package cn.photolib.audit;

import cn.photolib.common.api.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 新建类操作的 URL 里还没有对象 ID（{@code POST /projects}），ID 和名字只在返回值里。
 * 这里把要记日志的请求的返回数据挂到请求上，{@link AuditInterceptor} 从中取出
 * {@code id} 和标题/名称。只挂引用，不做序列化，也不改响应。
 */
@RestControllerAdvice
public class AuditResponseAdvice implements ResponseBodyAdvice<Object> {
    static final String RESPONSE_DATA_ATTRIBUTE = AuditResponseAdvice.class.getName() + ".data";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> envelope && envelope.data() != null
                && request instanceof ServletServerHttpRequest servlet
                && servlet.getServletRequest().getAttribute(AuditInterceptor.AUDITED_ATTRIBUTE) != null) {
            servlet.getServletRequest().setAttribute(RESPONSE_DATA_ATTRIBUTE, envelope.data());
        }
        return body;
    }
}
