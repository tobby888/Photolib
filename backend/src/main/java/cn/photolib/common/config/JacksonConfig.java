package cn.photolib.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * JavaScript can only represent integers exactly up to 2^53 - 1. MyBatis-Plus
 * generates Snowflake IDs that exceed that range, so unsafe values must cross
 * the JSON boundary as strings. Ordinary counters and file sizes remain numbers.
 */
@Configuration
public class JacksonConfig {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    @Bean
    JacksonModule safeLongModule() {
        ValueSerializer<Long> serializer = new ValueSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator generator, SerializationContext context)
                    throws JacksonException {
                if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
                    generator.writeString(value.toString());
                } else {
                    generator.writeNumber(value);
                }
            }
        };
        SimpleModule module = new SimpleModule("safe-long-module");
        module.addSerializer(Long.class, serializer);
        module.addSerializer(Long.TYPE, serializer);
        return module;
    }
}
