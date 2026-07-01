package cn.photolib.common.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTests {
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JacksonConfig().safeLongModule())
            .build();

    @Test
    void serializesSnowflakeIdsAsStringsWithoutChangingSafeCounters() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "id", 2_072_210_596_807_360_514L,
                "count", 42L));

        assertThat(json).contains("\"id\":\"2072210596807360514\"");
        assertThat(json).contains("\"count\":42");
    }
}
