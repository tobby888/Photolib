package cn.photolib.storage;

import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OssAccessMonitorTests {
    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private final StorageProperties properties = mock(StorageProperties.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private OssAccessMonitor monitor;

    @BeforeEach
    void configure() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = JdbcClient.create(database);
        jdbc.sql("""
                CREATE TABLE admin_alert (
                    type VARCHAR(64), message VARCHAR(512), resource_type VARCHAR(32),
                    resolved INTEGER, created_at TIMESTAMP, resolved_at TIMESTAMP
                )
                """).update();
        when(properties.ossConfigured()).thenReturn(true);
        monitor = new OssAccessMonitor(properties, storage, jdbc);
    }

    @AfterEach
    void close() { database.shutdown(); }

    @Test
    void invalidCredentialsProduceOneSanitizedAlertAndSuccessfulProbeResolvesIt() {
        OSSException missingKey = error("InvalidAccessKeyId");
        OSSException wrongSecret = error("SignatureDoesNotMatch");
        doThrow(missingKey, wrongSecret).doNothing().when(storage).checkAccess();
        monitor.check();
        monitor.check();
        assertThat(active(OssAccessMonitor.CREDENTIAL_ALERT)).singleElement()
                .asString().contains("OSS_ACCESS_KEY_ID").doesNotContain("private-provider-details");
        monitor.check();
        assertThat(active(OssAccessMonitor.CREDENTIAL_ALERT)).isEmpty();
    }

    @Test
    void successfulBucketProbeDoesNotHideMissingConversionPermissions() {
        monitor.conversionFailed(error("AccessDenied"));
        monitor.check();
        assertThat(active(OssAccessMonitor.CONVERSION_ALERT)).singleElement()
                .asString().contains("oss:PostProcessTask");
        monitor.conversionSucceeded();
        assertThat(active(OssAccessMonitor.CONVERSION_ALERT)).isEmpty();
    }

    @Test
    void transientNetworkFailureDoesNotDeclareCredentialsInvalid() {
        doThrow(new RuntimeException("timeout")).when(storage).checkAccess();
        monitor.check();
        assertThat(active(OssAccessMonitor.CREDENTIAL_ALERT)).isEmpty();
    }

    @Test
    void missingConfigurationIsVisibleAndLocalModeSkipsOss() {
        when(properties.ossConfigured()).thenReturn(false);
        when(properties.local()).thenReturn(true);
        monitor.check();
        assertThat(active(OssAccessMonitor.CREDENTIAL_ALERT)).isEmpty();
        when(properties.local()).thenReturn(false);
        monitor.check();
        assertThat(active(OssAccessMonitor.CREDENTIAL_ALERT)).singleElement()
                .asString().contains("配置不完整");
        verifyNoInteractions(storage);
    }

    private java.util.List<String> active(String type) {
        return jdbc.sql("SELECT message FROM admin_alert WHERE type=:type AND resolved=0")
                .param("type", type).query(String.class).list();
    }

    private OSSException error(String code) {
        OSSException failure = mock(OSSException.class);
        when(failure.getErrorCode()).thenReturn(code);
        when(failure.getMessage()).thenReturn("private-provider-details");
        return failure;
    }
}
