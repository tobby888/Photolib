package cn.photolib.spa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 打进 JAR 的前端的缓存头，验的是 {@link cn.photolib.StaticResourceConfig} 和 Spring Security
 * 默认缓存头叠在一起之后的结果：只看配置类看不出来 Security 会不会把它覆盖成 no-store。
 *
 * <p>夹具在 {@code src/test/resources/static}，测试类路径排在主类路径前面，所以
 * {@code index.html} 用的是夹具而不是真实构建产物。{@code .br} / {@code .gz} 夹具是可读文本：
 * 选哪个变体只看文件名，不看内容，文本内容正好能直接断言返回的是哪一份。
 */
@SpringBootTest
class StaticResourceCacheHttpTests {

    private static final String ASSET = "/assets/cache-probe-T3st1234.js";

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void hashedAssetsAreCachedForeverAndServedPrecompressed() throws Exception {
        // Chrome 的原样请求头：br 排在 gzip 后面，照样要给 br。
        MvcResult brotli = mvc.perform(get(ASSET).header(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br, zstd"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "br"))
                .andExpect(content().string("/* br variant */\n"))
                .andReturn();
        assertImmutable(brotli);
        assertThat(brotli.getResponse().getContentType()).contains("javascript");
        assertThat(brotli.getResponse().getHeaders(HttpHeaders.VARY)).contains(HttpHeaders.ACCEPT_ENCODING);

        // 明文 http 下浏览器不声明 br，只能给 gzip。
        MvcResult gzip = mvc.perform(get(ASSET).header(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andExpect(content().string("/* gzip variant */\n"))
                .andReturn();
        assertImmutable(gzip);

        // 明确拒绝 br 的客户端不能拿到 br。
        mvc.perform(get(ASSET).header(HttpHeaders.ACCEPT_ENCODING, "br;q=0, gzip"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"));

        MvcResult identity = mvc.perform(get(ASSET))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING))
                .andExpect(content().string("console.log('identity variant')\n"))
                .andReturn();
        assertImmutable(identity);
    }

    @Test
    void missingAssetIsNotCachedAsPermanentlyAbsent() throws Exception {
        // 发版后旧页面去要已被替换的 chunk：这个 404 不能被当成"一年内都不存在"缓存下来。
        MvcResult missing = mvc.perform(get("/assets/removed-in-last-release-0000.js"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(missing.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store").doesNotContain("immutable");
    }

    @Test
    void entryDocumentIsRevalidatedByContentDigest() throws Exception {
        MvcResult first = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        mvc.perform(get("/index.html").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/index.html").header(HttpHeaders.IF_NONE_MATCH, "\"from-an-older-release\""))
                .andExpect(status().isOk());
    }

    private static void assertImmutable(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("max-age=31536000, public, immutable");
        assertThat(result.getResponse().getHeader("Pragma")).isNull();
    }
}
