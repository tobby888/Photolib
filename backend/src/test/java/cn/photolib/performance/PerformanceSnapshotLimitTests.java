package cn.photolib.performance;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件数到上限时快照自动停止并导出——防止一次忘了关的快照把数据库磁盘写满。
 * 上限改成 3 条要单独一个上下文，放在共享上下文里会让别的用例记到一半被停掉。
 */
@SpringBootTest(properties = "photolib.performance.snapshot-max-events=3")
class PerformanceSnapshotLimitTests {
    @Autowired private PerformanceSnapshotService snapshots;
    @Autowired private SnapshotRecorder recorder;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectStorageService storage;

    @Test
    void snapshotStopsItselfWhenTheEventLimitIsReached() throws Exception {
        AuthenticatedUser admin = new AuthenticatedUser(97_403L, "perf-limit", "上限测试", UserRole.ADMIN, null, false);
        String id = snapshots.start(admin, 60, null).id();
        try {
            for (int i = 0; i < 10; i++) {
                jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
            }
            long deadline = System.currentTimeMillis() + 30_000;
            while (!"COMPLETED".equals(status(id))) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("快照没有在上限处自动停止");
                Thread.sleep(50);
            }
            assertThat(recorder.active()).isFalse();
            assertThat(jdbc.sql("SELECT stop_reason FROM perf_snapshot_session WHERE id = :id")
                    .param("id", id).query(String.class).single()).isEqualTo("LIMIT");
        } finally {
            for (String column : new String[]{"events_object_key", "samples_object_key"}) {
                String key = jdbc.sql("SELECT " + column + " FROM perf_snapshot_session WHERE id = :id")
                        .param("id", id).query(String.class).optional().orElse(null);
                if (key != null) {
                    storage.delete(key);
                    storage.delete(key.replaceAll("[^/]+$", "session.csv"));
                }
            }
            jdbc.sql("DELETE FROM perf_snapshot_event WHERE session_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM perf_snapshot_sample WHERE session_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM perf_snapshot_session WHERE id = :id").param("id", id).update();
        }
    }

    private String status(String id) {
        return jdbc.sql("SELECT status FROM perf_snapshot_session WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }
}
