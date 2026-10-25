package cn.photolib.performance;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.dashboard.RequestMetricsFilter;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 性能分析走完整过滤器链：谁能看、实时窗口和一周分位数里有没有真实请求、性能快照从开始到
 * CSV 落进对象存储再到删除库内明细的整条链路，以及到点自动停止和重启后的收尾。
 *
 * <p>不加 {@code @Transactional}：快照的写库和导出都在后台线程上，测试事务里的数据它们看不见。
 * 每个用例自己清理快照记录和对象。</p>
 */
@SpringBootTest
class PerformanceHttpTests {
    private static final long ADMIN_ID = 97_401L;
    private static final long MINISTER_ID = 97_402L;
    private static final String LAYOUT = """
            {"layout":{"refreshSeconds":10,"widgets":[
              {"id":"memo","type":"note","title":"值班","size":"small","text":"性能测试"}
            ]},"version":null}
            """;

    private MockMvc mvc;
    private final List<String> createdSessions = new ArrayList<>();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private RequestMetricsFilter metricsFilter;
    @Autowired private PerformanceMonitor monitor;
    @Autowired private LatencyStatsStore stats;
    @Autowired private SnapshotRecorder recorder;
    @Autowired private PerformanceSnapshotService snapshots;
    @Autowired private ObjectStorageService storage;
    @Autowired private DataSource dataSource;
    @Autowired private Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(metricsFilter).apply(springSecurity()).build();
        insertUserIfMissing(ADMIN_ID, "perf-admin", "性能管理员", "ADMIN");
        insertUserIfMissing(MINISTER_ID, "perf-minister", "部长", "MINISTER");
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "perf-admin", "性能管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(MINISTER_ID, "perf-minister", "部长", UserRole.MINISTER, null, false)));
    }

    @AfterEach
    void cleanUp() throws Exception {
        String active = recorder.sessionId();
        if (active != null) {
            ReflectionTestUtils.invokeMethod(snapshots, "stopInternal", active,
                    PerformanceSnapshotService.REASON_MANUAL, null);
            createdSessions.add(active);
        }
        for (String id : createdSessions) {
            waitUntil(() -> !"EXPORTING".equals(statusOf(id)), 30_000);
            for (String key : jdbc.sql("""
                            SELECT events_object_key FROM perf_snapshot_session WHERE id = :id
                            UNION ALL SELECT samples_object_key FROM perf_snapshot_session WHERE id = :id
                            """).param("id", id).query(String.class).list()) {
                if (key == null) continue;
                storage.delete(key);
                storage.delete(key.replaceAll("[^/]+$", "session.csv"));
            }
            jdbc.sql("DELETE FROM perf_snapshot_event WHERE session_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM perf_snapshot_sample WHERE session_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM perf_snapshot_session WHERE id = :id").param("id", id).update();
        }
        // 这个类不在测试事务里，审计行和两个账号会留在共享的 H2 库里：别的测试（按操作人数审计行、
        // 广播给全部账号）会多数出来。全部删掉。
        jdbc.sql("DELETE FROM admin_dashboard_layout WHERE user_id IN (:ids)")
                .param("ids", List.of(ADMIN_ID, MINISTER_ID)).update();
        jdbc.sql("DELETE FROM audit_log WHERE operator_id IN (:ids)")
                .param("ids", List.of(ADMIN_ID, MINISTER_ID)).update();
        jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)")
                .param("ids", List.of(ADMIN_ID, MINISTER_ID)).update();
    }

    @Test
    void dataSourceAndStorageAreWrappedForTiming() {
        assertThat(dataSource).isInstanceOf(TimedDataSource.class);
        assertThat(storage).isInstanceOf(TimedObjectStorageService.class);
    }

    @Test
    void onlyAdministratorsCanUsePerformanceAnalysis() throws Exception {
        mvc.perform(anonymous(get("/api/v1/performance/live"))).andExpect(status().isUnauthorized());
        mvc.perform(as("minister", get("/api/v1/performance/live"))).andExpect(status().isForbidden());
        mvc.perform(as("minister", get("/api/v1/performance/weekly"))).andExpect(status().isForbidden());
        mvc.perform(as("minister", get("/api/v1/performance/snapshots"))).andExpect(status().isForbidden());
        mvc.perform(as("minister", post("/api/v1/performance/snapshots")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationMinutes\":5}")))
                .andExpect(status().isForbidden());
        assertThat(recorder.active()).isFalse();
    }

    @Test
    void liveWindowShowsEveryCallWithItsFeatureNameAndModule() throws Exception {
        mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                .contentType(MediaType.APPLICATION_JSON).content(LAYOUT))).andExpect(status().isOk());
        monitor.recordImage(PerformanceMonitor.IMAGE_THUMBNAIL, 250_000_000L, true, "源 image/jpeg 1 B");

        mvc.perform(as("admin", get("/api/v1/performance/live").param("windowSeconds", "60")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowSeconds").value(60))
                .andExpect(jsonPath("$.data.features[*].key").value(hasItem("PUT /api/v1/admin-dashboard/layout")))
                .andExpect(jsonPath("$.data.features[?(@.key == 'PUT /api/v1/admin-dashboard/layout')].label")
                        .value(hasItem("保存管理面板布局")))
                .andExpect(jsonPath("$.data.features[?(@.key == 'PUT /api/v1/admin-dashboard/layout')].module")
                        .value(hasItem("管理面板")))
                // 面板自己的轮询不进统计。
                .andExpect(jsonPath("$.data.features[*].key").value(everyItem(not(startsWith("GET /api/v1/performance")))))
                .andExpect(jsonPath("$.data.images[*].key").value(hasItem("THUMBNAIL")))
                .andExpect(jsonPath("$.data.thumbnails.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.recent[*].label").value(hasItem("生成缩略图")));

        mvc.perform(as("admin", get("/api/v1/performance/live").param("windowSeconds", "601")))
                .andExpect(status().isBadRequest());
        mvc.perform(as("admin", get("/api/v1/performance/live").param("windowSeconds", "4")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weeklyPercentilesIncludeUnflushedDataAndDoNotDoubleCountAfterFlush() throws Exception {
        mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                .contentType(MediaType.APPLICATION_JSON).content(LAYOUT))).andExpect(status().isOk());
        monitor.recordImage(PerformanceMonitor.IMAGE_COMPRESS, 1_500_000_000L, true, "源 image/jpeg 1 B");

        JsonNode before = data(mvc.perform(as("admin", get("/api/v1/performance/weekly")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7)))
                .andReturn().getResponse().getContentAsString());
        long requestsBefore = before.at("/requests/count").asLong();
        long compressBefore = before.at("/compressions/count").asLong();
        assertThat(requestsBefore).isPositive();
        assertThat(compressBefore).isPositive();
        assertThat(before.at("/compressions/p99Ms").asDouble()).isPositive();
        assertThat(before.at("/features").findValuesAsText("key")).contains("PUT /api/v1/admin-dashboard/layout");

        stats.flush();
        JsonNode after = data(mvc.perform(as("admin", get("/api/v1/performance/weekly")))
                .andReturn().getResponse().getContentAsString());
        // 落库前后同一批数据只算一次（中间多出来的只有 weekly 自身之外的请求，这里没有）。
        assertThat(after.at("/compressions/count").asLong()).isEqualTo(compressBefore);
        assertThat(after.at("/requests/count").asLong()).isEqualTo(requestsBefore);

        // 再落一次库：已有的小时行被合并，不新增重复行。
        monitor.recordImage(PerformanceMonitor.IMAGE_COMPRESS, 1_000_000L, true, null);
        stats.flush();
        Long rows = jdbc.sql("""
                SELECT COUNT(*) FROM perf_latency_hourly WHERE kind = 'IMAGE' AND op_key = 'COMPRESS'
                AND hour_start = (SELECT MAX(hour_start) FROM perf_latency_hourly WHERE op_key = 'COMPRESS')
                """).query(Long.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void snapshotRecordsSqlStorageImageAndSystemSamplesThenExportsCsvAndDeletesRows() throws Exception {
        String id = data(mvc.perform(as("admin", post("/api/v1/performance/snapshots")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"durationMinutes\":5,\"note\":\"压测前基线\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECORDING"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        createdSessions.add(id);
        assertThat(recorder.sessionId()).isEqualTo(id);

        // 同时只能有一个；时长不能超过 72 小时。
        mvc.perform(as("admin", post("/api/v1/performance/snapshots")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationMinutes\":5}")))
                .andExpect(status().isConflict());
        mvc.perform(as("admin", post("/api/v1/performance/snapshots")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationMinutes\":4321}")))
                .andExpect(status().isBadRequest());

        // 一次真实请求（带 SQL）、一次存储写入和读取、一次压缩原图。
        mvc.perform(as("admin", get("/api/v1/performance/snapshots"))).andExpect(status().isOk());
        String objectKey = "performance-test/" + id + ".txt";
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        storage.put(objectKey, new ByteArrayInputStream(content), content.length, "text/plain");
        try (InputStream in = storage.open(objectKey)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        storage.delete(objectKey);
        monitor.recordImage(PerformanceMonitor.IMAGE_COMPRESS, 42_000_000L, true, "源 image/png 9 B");
        waitUntil(() -> recorder.writtenSamples() >= 1 && recorder.queuedEvents() == 0
                && recorder.writtenEvents() >= 5, 15_000);

        mvc.perform(as("admin", get("/api/v1/performance/snapshots/current")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.id").value(id))
                .andExpect(jsonPath("$.data.maxDurationMinutes").value(4320));

        mvc.perform(as("admin", post("/api/v1/performance/snapshots/" + id + "/stop")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stopReason").value("MANUAL"))
                .andExpect(jsonPath("$.data.stoppedByName").value("性能管理员"));
        assertThat(recorder.active()).isFalse();
        waitUntil(() -> "COMPLETED".equals(statusOf(id)), 30_000);

        // 库内明细已删，记录与 CSV 保留。
        assertThat(count("perf_snapshot_event", id)).isZero();
        assertThat(count("perf_snapshot_sample", id)).isZero();
        String eventsKey = jdbc.sql("SELECT events_object_key FROM perf_snapshot_session WHERE id = :id")
                .param("id", id).query(String.class).single();
        String samplesKey = jdbc.sql("SELECT samples_object_key FROM perf_snapshot_session WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(eventsKey).startsWith("performance-snapshots/").endsWith("/events.csv");

        String events = read(eventsKey);
        assertThat(events).startsWith("﻿\"时间\",\"类别\"");
        assertThat(events).contains("\"MARK\",\"START\"").contains("\"MARK\",\"STOP\"");
        assertThat(events).contains("\"HTTP\",\"GET\"").contains("/api/v1/performance/snapshots");
        // SQL 原文带 ? 占位符，并标出是哪个接口触发的。
        assertThat(events).contains("\"SQL\",\"SELECT\"").contains("FROM perf_snapshot_session");
        assertThat(events).contains("\"GET /api/v1/performance/snapshots\"");
        assertThat(events).contains("\"STORAGE\",\"put\"").contains("\"STORAGE\",\"open\"")
                .contains("\"STORAGE\",\"delete\"").contains(objectKey);
        assertThat(events).contains("\"IMAGE\",\"COMPRESS\",\"42.000\"");
        // 记录器自己写事件的 INSERT 不能被记成事件。
        assertThat(events).doesNotContain("INSERT INTO perf_snapshot_event");

        String samples = read(samplesKey);
        assertThat(samples).startsWith("﻿\"时间\",\"进程CPU(%)\"");
        assertThat(samples.split("\r\n").length).isGreaterThanOrEqualTo(2);
        assertThat(read(eventsKey.replace("events.csv", "session.csv"))).contains("压测前基线").contains("管理员手动停止");

        mvc.perform(as("admin", get("/api/v1/performance/snapshots/" + id + "/download").param("file", "samples")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andExpect(jsonPath("$.data.fileName").value(org.hamcrest.Matchers.endsWith("-samples.csv")));
        mvc.perform(as("admin", get("/api/v1/performance/snapshots/" + id + "/download").param("file", "bogus")))
                .andExpect(status().isBadRequest());
        mvc.perform(as("admin", post("/api/v1/performance/snapshots/" + id + "/retry-export")))
                .andExpect(status().isConflict());
        mvc.perform(as("admin", post("/api/v1/performance/snapshots/" + id + "/stop")))
                .andExpect(status().isConflict());

        mvc.perform(as("admin", get("/api/v1/performance/snapshots")))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + id + "')].downloadable").value(hasItem(true)));

        List<String> audited = jdbc.sql("""
                SELECT action_name FROM audit_log WHERE resource_type = 'PERFORMANCE' AND operator_id = :operator
                """).param("operator", ADMIN_ID).query(String.class).list();
        assertThat(audited).contains("开始性能快照记录", "停止性能快照记录", "下载性能快照");
    }

    @Test
    void snapshotStopsAutomaticallyWhenThePlannedDurationIsReached() throws Exception {
        PerformanceSnapshotService.SessionView started = snapshots.start(admin(), 1, null);
        createdSessions.add(started.id());
        ReflectionTestUtils.setField(snapshots, "plannedEnd", LocalDateTime.now(clock).minusSeconds(1));

        snapshots.tick();

        assertThat(recorder.active()).isFalse();
        waitUntil(() -> "COMPLETED".equals(statusOf(started.id())), 30_000);
        assertThat(jdbc.sql("SELECT stop_reason FROM perf_snapshot_session WHERE id = :id")
                .param("id", started.id()).query(String.class).single()).isEqualTo("TIMEOUT");
    }

    @Test
    void afterRestartAnExpiredSnapshotIsExportedAndAnUnexpiredOneResumes() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        String expired = "PERFTESTEXPIRED00000000000";
        String running = "PERFTESTRUNNING00000000000";
        createdSessions.add(expired);
        createdSessions.add(running);
        insertSession(expired, now.minusHours(2), now.minusHours(1));
        insertSession(running, now.minusMinutes(5), now.plusHours(1));
        jdbc.sql("""
                INSERT INTO perf_snapshot_event (session_id, occurred_at, category, operation, duration_micros, success,
                                                 target, amount)
                VALUES (:id, :at, 'SQL', 'SELECT', 1234, TRUE, 'SELECT 1 FROM 停机前', NULL)
                """).param("id", expired).param("at", now.minusMinutes(90)).update();

        snapshots.resume();

        assertThat(recorder.sessionId()).isEqualTo(running);
        waitUntil(() -> "COMPLETED".equals(statusOf(expired)), 30_000);
        String eventsKey = jdbc.sql("SELECT events_object_key FROM perf_snapshot_session WHERE id = :id")
                .param("id", expired).query(String.class).single();
        assertThat(read(eventsKey)).contains("SELECT 1 FROM 停机前").contains("\"1.234\"");
        assertThat(count("perf_snapshot_event", expired)).isZero();

        snapshots.stop(running, admin());
        waitUntil(() -> "COMPLETED".equals(statusOf(running)), 30_000);
        assertThat(read(jdbc.sql("SELECT events_object_key FROM perf_snapshot_session WHERE id = :id")
                .param("id", running).query(String.class).single())).contains("\"MARK\",\"RESUME\"");
    }

    // ------------------------------------------------------------------ helpers

    private AuthenticatedUser admin() {
        return new AuthenticatedUser(ADMIN_ID, "perf-admin", "性能管理员", UserRole.ADMIN, null, false);
    }

    private void insertSession(String id, LocalDateTime startedAt, LocalDateTime plannedEnd) {
        jdbc.sql("""
                INSERT INTO perf_snapshot_session (id, status, started_at, planned_end_at, sample_interval_seconds)
                VALUES (:id, 'RECORDING', :startedAt, :plannedEnd, 5)
                """).param("id", id).param("startedAt", startedAt).param("plannedEnd", plannedEnd).update();
    }

    private void insertUserIfMissing(long id, String username, String name, String role) {
        Long existing = jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = :id").param("id", id)
                .query(Long.class).single();
        if (existing > 0) return;
        jdbc.sql("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, campus_id, enabled,
                                      must_change_password)
                VALUES (:id, :username, 'hash', :name, :role, NULL, TRUE, FALSE)
                """).param("id", id).param("username", username).param("name", name).param("role", role).update();
    }

    private String statusOf(String id) {
        return jdbc.sql("SELECT status FROM perf_snapshot_session WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private long count(String table, String id) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE session_id = :id").param("id", id)
                .query(Long.class).single();
    }

    private String read(String key) throws Exception {
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JsonNode data(String body) throws Exception {
        return json.readTree(body).get("data");
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("等待超时");
            Thread.sleep(50);
        }
    }

    private static MockHttpServletRequestBuilder anonymous(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return anonymous(builder.header("Authorization", "Bearer " + token));
    }
}
