package cn.photolib.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 请求参数进日志之前的整理：凭据一律打码，长内容截断。 */
class AuditPayloadsTests {
    private static JsonNode body(String json, String path) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return AuditPayloads.body(bytes, bytes.length, path);
    }

    @Test
    void credentialsAreMaskedWhereverTheyAppear() {
        JsonNode cleaned = body("""
                {"username":"alice","password":"p","newPassword":"p2","mfa":{"code":"123456","assertion":{"x":1}},
                 "planToken":"tk","deviceCode":"dc","userCode":"ABCD-EFGH","ticket":"t",
                 "items":[{"secretKey":"s","title":"留着"}]}
                """, "/api/v1/auth/login");

        assertThat(cleaned.path("username").asText()).isEqualTo("alice");
        for (String masked : List.of("/password", "/newPassword", "/mfa/code", "/mfa/assertion", "/planToken",
                "/deviceCode", "/userCode", "/ticket", "/items/0/secretKey")) {
            assertThat(cleaned.at(masked).asText()).as(masked).isEqualTo(AuditPayloads.MASK);
        }
        assertThat(cleaned.at("/items/0/title").asText()).isEqualTo("留着");
    }

    @Test
    void publicCodesStayReadable() {
        assertThat(body("{\"code\":\"EAST\"}", "/api/v1/campuses").path("code").asText()).isEqualTo("EAST");
        assertThat(body("{\"code\":\"EDITOR\"}", "/api/v1/permission-groups/3").path("code").asText())
                .isEqualTo("EDITOR");
        // 注册码是能换来账号的东西。
        assertThat(body("{\"code\":\"R3G-CODE\"}", "/api/v1/public/registrations").path("code").asText())
                .isEqualTo(AuditPayloads.MASK);
    }

    @Test
    void longTextAndLargeArraysAreTrimmed() {
        String longText = "字".repeat(AuditPayloads.MAX_STRING + 50);
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < 100; i++) ids.append(i == 0 ? "" : ",").append(i);
        JsonNode cleaned = body("{\"content\":\"" + longText + "\",\"photoIds\":" + ids + "]}", "/api/v1/docs/1/content");

        assertThat(cleaned.path("content").asText()).endsWith("（共 " + longText.length() + " 字）");
        assertThat(cleaned.path("photoIds")).hasSize(AuditPayloads.MAX_ARRAY + 1);
        assertThat(cleaned.path("photoIds").get(AuditPayloads.MAX_ARRAY).asText()).isEqualTo("…共 100 项");
    }

    @Test
    void unreadableBodiesAreDescribedNotCopied() {
        assertThat(body("not json {", "/api/v1/x").asText()).contains("不是可解析的 JSON");
        assertThat(AuditPayloads.body(new byte[0], 0, "/api/v1/x")).isNull();
        assertThat(AuditPayloads.body(new byte[0], 70_000, "/api/v1/x").asText()).contains("70000 字节");
    }

    @Test
    void queryParametersAreMaskedToo() {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("keyword", new String[]{"春季"});
        params.put("token", new String[]{"abc"});
        params.put("ids", new String[]{"1", "2"});

        Map<String, Object> cleaned = AuditPayloads.parameters(params, "/api/v1/photos");

        assertThat(cleaned).containsEntry("keyword", "春季").containsEntry("token", AuditPayloads.MASK)
                .containsEntry("ids", List.of("1", "2"));
    }
}
