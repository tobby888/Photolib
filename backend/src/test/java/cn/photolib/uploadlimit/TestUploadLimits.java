package cn.photolib.uploadlimit;

import java.util.EnumMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 不起 Spring 上下文的单元测试用：按内置默认值（可逐项覆盖）回答的 {@link UploadLimitService}。 */
public final class TestUploadLimits {
    private TestUploadLimits() {
    }

    public static UploadLimitService defaults() {
        return with(Map.of());
    }

    public static UploadLimitService with(UploadLimit limit, long value) {
        return with(Map.of(limit, value));
    }

    public static UploadLimitService with(Map<UploadLimit, Long> overrides) {
        Map<UploadLimit, Long> values = new EnumMap<>(UploadLimit.class);
        for (UploadLimit limit : UploadLimit.values()) values.put(limit, limit.defaultValue());
        values.putAll(overrides);
        UploadLimitService service = mock(UploadLimitService.class);
        when(service.value(any())).thenAnswer(call -> values.get(call.<UploadLimit>getArgument(0)));
        // count / describe 是基于 value 的换算，走真实实现，读到的就是上面这份值。
        when(service.count(any())).thenCallRealMethod();
        when(service.describe(any())).thenCallRealMethod();
        return service;
    }
}
