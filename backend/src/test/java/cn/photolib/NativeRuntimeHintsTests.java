package cn.photolib;

import cn.photolib.admin.BrandingController;
import cn.photolib.auth.AuthProperties;
import cn.photolib.auth.DeploymentSecurityValidator;
import cn.photolib.auth.mfa.WebAuthnSupport;
import cn.photolib.common.config.MybatisConfig;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.dashboard.DashboardLayoutService;
import cn.photolib.featured.mapper.FeaturedCollectionMapper;
import cn.photolib.featured.FeaturedCollectionService;
import cn.photolib.featured.FeaturedDeadlineJob;
import cn.photolib.feedback.model.FeedbackCategory;
import cn.photolib.feedback.model.FeedbackStatus;
import cn.photolib.form.FormFileStatus;
import cn.photolib.notification.UserNotificationController;
import cn.photolib.notification.wecom.WeComConfig;
import cn.photolib.notification.wecom.WeComMessageType;
import cn.photolib.performance.PerformanceConfig;
import cn.photolib.photo.ImageCompressor;
import cn.photolib.storage.LocalStorageController;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageConfigValidator;
import cn.photolib.teaching.model.TeachingMaterialFormat;
import cn.photolib.uploadlimit.UploadLimitController;
import cn.photolib.uploadlimit.UploadLimitService;
import cn.photolib.user.AdminBootstrap;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.worklog.model.WorklogSource;
import com.aliyuncs.AcsError;
import com.aliyuncs.dm.model.v20151123.SingleSendMailResponse;
import com.aliyuncs.http.clients.ApacheHttpClient;
import com.aliyuncs.http.clients.CompatibleUrlConnClient;
import com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.authenticator.EdDSACOSEKey;
import com.webauthn4j.data.attestation.authenticator.RSACOSEKey;
import com.webauthn4j.data.attestation.statement.AttestationCertificatePath;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.Response;
import com.webauthn4j.data.client.CollectedClientData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.type.EnumOrdinalTypeHandler;
import org.apache.ibatis.type.EnumTypeHandler;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 守住 GraalVM Native Image 关键运行时提示，防止后续重构把实机验证过的反射、资源、代理或 JNI 注册删漏。
 */
class NativeRuntimeHintsTests {

    @Test
    void registersCoreResourcesPoiAliyunAndAwtJniHints() {
        RuntimeHints hints = collectHints(PhotoLibApplication.class);

        assertThat(RuntimeHintsPredicates.resource().forResource("static/index.html")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("db/migration/V1__initial_schema.sql")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("mail/settlement-default.html")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("org/apache/poi/schemas/ooxml/system/ooxml/index.xsb")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("org/apache/xmlbeans/metadata/system/sXMLLANG/index.xsb")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("endpoints.json")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("versioninfo.properties")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/services/org.flywaydb.core.extensibility.Plugin")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forBundle("oss")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forBundle("common")).accepts(hints);

        assertThat(RuntimeHintsPredicates.reflection().onType(CTPicture[].class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(SingleSendMailResponse.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(AcsError.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(ApacheHttpClient.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(CompatibleUrlConnClient.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("org.flywaydb.database.mysql.MySQLDatabaseType"))
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("org.flywaydb.database.mysql.mariadb.MariaDBDatabaseType"))
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.CTWorkbookImpl"))
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("sun.font.HBShaper"))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(hints);

        assertThat(hints.jni().typeHints().map(h -> h.getType().getName()))
                .contains("java.lang.System",
                        "java.awt.GraphicsEnvironment",
                        "com.sun.imageio.plugins.jpeg.JPEGImageReader",
                        "com.sun.imageio.plugins.jpeg.JPEGImageWriter",
                        "sun.font.Font2D");
    }

    @Test
    void registersMyBatisEnumHandlersPerformanceRowsWeComAndWebAuthnHints() {
        RuntimeHints mybatisHints = collectHints(MybatisConfig.class);
        for (Class<?> handler : new Class<?>[]{
                CompositeEnumTypeHandler.class, MybatisEnumTypeHandler.class,
                EnumTypeHandler.class, EnumOrdinalTypeHandler.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(handler)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(mybatisHints);
        }
        for (Class<?> ognlType : new Class<?>[]{
                SystemMetaObject.class, MetaObject.class,
                Collection.class, List.class, Set.class, Map.class,
                Set.of().getClass(), Set.of(1L).getClass(), Set.of(1L, 2L, 3L).getClass(),
                List.of(1L).getClass(), List.of(1L, 2L, 3L).getClass(),
                Collections.emptySet().getClass(), Collections.singleton(1L).getClass()}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(ognlType)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)).accepts(mybatisHints);
            assertThat(RuntimeHintsPredicates.reflection().onType(ognlType)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(mybatisHints);
        }
        for (Class<?> enumType : new Class<?>[]{
                FeedbackCategory.class, FeedbackStatus.class, FormFileStatus.class,
                TeachingMaterialFormat.class, WorklogSource.class, WeComMessageType.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(enumType)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)).accepts(mybatisHints);
            assertThat(RuntimeHintsPredicates.reflection().onType(enumType)
                    .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS)).accepts(mybatisHints);
        }

        RuntimeHints perfHints = collectHints(PerformanceConfig.class);
        for (Class<?> jdbcType : new Class<?>[]{
                Connection.class, Statement.class, PreparedStatement.class, CallableStatement.class}) {
            assertThat(RuntimeHintsPredicates.proxies().forInterfaces(jdbcType)).accepts(perfHints);
        }
        for (String rowType : new String[]{
                "cn.photolib.performance.LatencyStatsStore$Row",
                "cn.photolib.performance.PerformanceSnapshotService$Row"}) {
            TypeReference ref = TypeReference.of(rowType);
            assertThat(RuntimeHintsPredicates.reflection().onType(ref)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(perfHints);
            assertThat(RuntimeHintsPredicates.reflection().onType(ref)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(perfHints);
        }

        RuntimeHints weComHints = collectHints(WeComConfig.class);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("cn.photolib.notification.wecom.WeComApi$ApiResult"))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(weComHints);

        RuntimeHints notificationHints = collectHints(UserNotificationController.class);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("cn.photolib.notification.UserNotificationController$SendMessageRequest"))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(notificationHints);

        RuntimeHints uploadLimitHints = collectHints(UploadLimitController.class);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(UploadLimitService.Change.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(uploadLimitHints);

        RuntimeHints dashboardHints = collectHints(DashboardLayoutService.class);
        for (Class<?> type : new Class<?>[]{DashboardLayoutService.Layout.class, DashboardLayoutService.Widget.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(dashboardHints);
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(dashboardHints);
        }

        RuntimeHints brandingHints = collectHints(BrandingController.class);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(BrandingController.FooterLink.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(brandingHints);

        RuntimeHints webAuthnHints = collectHints(WebAuthnSupport.class);
        for (Class<?> type : new Class<?>[]{
                CollectedClientData.class, Origin.class, DefaultChallenge.class,
                AuthenticatorTransport.class, COSEAlgorithmIdentifier.class,
                AttestationObject.class, AttestationCertificatePath.class, Response.class,
                EC2COSEKey.class, RSACOSEKey.class, EdDSACOSEKey.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(webAuthnHints);
        }
    }

    @Test
    void aotSensitiveBeansUseRuntimeSwitchesInsteadOfBuildTimeConditions() {
        for (Class<?> beanType : new Class<?>[]{
                LocalStorageController.class,
                StorageConfigValidator.class,
                DeploymentSecurityValidator.class,
                AdminBootstrap.class,
                FeaturedDeadlineJob.class}) {
            assertThat(AnnotatedElementUtils.hasAnnotation(beanType, ConditionalOnProperty.class))
                    .as("%s 不应使用 @ConditionalOnProperty（避免 Spring AOT 固化）", beanType.getSimpleName())
                    .isFalse();
            assertThat(AnnotatedElementUtils.hasAnnotation(beanType, Profile.class))
                    .as("%s 不应使用 @Profile（避免 Spring AOT 固化）", beanType.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    void runtimeSwitchesRespectEnvironmentAndFlags() {
        ObjectStorageService ossStorage = mock(ObjectStorageService.class);
        LocalStorageController controller = new LocalStorageController(ossStorage, mock(UploadLimitService.class));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(controller, "download", "any-token"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        AuthProperties insecureAuth = new AuthProperties(Duration.ofMinutes(15), Duration.ofMinutes(30), false, null);
        MockEnvironment localEnv = new MockEnvironment().withProperty("spring.profiles.active", "local");
        localEnv.setActiveProfiles("local");
        DeploymentSecurityValidator localValidator = new DeploymentSecurityValidator(insecureAuth, localEnv);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(localValidator, "validate")).doesNotThrowAnyException();

        MockEnvironment prodEnv = new MockEnvironment();
        DeploymentSecurityValidator prodValidator = new DeploymentSecurityValidator(insecureAuth, prodEnv);
        ReflectionTestUtils.setField(prodValidator, "bootstrapEnabled", true);
        ReflectionTestUtils.setField(prodValidator, "adminPassword", "ChangeMe123!");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(prodValidator, "validate"))
                .isInstanceOf(IllegalStateException.class);

        UserMapper userMapper = mock(UserMapper.class);
        AdminBootstrap bootstrap = new AdminBootstrap(userMapper, null, null);
        ReflectionTestUtils.setField(bootstrap, "enabled", false);
        bootstrap.run(new DefaultApplicationArguments());
        verifyNoInteractions(userMapper);

        FeaturedCollectionMapper featuredMapper = mock(FeaturedCollectionMapper.class);
        FeaturedDeadlineJob job = new FeaturedDeadlineJob(featuredMapper, mock(FeaturedCollectionService.class));
        ReflectionTestUtils.setField(job, "enabled", false);
        ReflectionTestUtils.invokeMethod(job, "closeExpiredScheduled");
        verifyNoInteractions(featuredMapper);
    }

    @Test
    void nativeImageProcessorHintsOnlyIncludeCurrentPlatformBinaries() {
        RuntimeHints hints = collectHints(ImageCompressor.class);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            assertThat(RuntimeHintsPredicates.resource().forResource("native/linux-x86_64/libphotolib-image.so")).accepts(hints);
            assertThat(RuntimeHintsPredicates.resource().forResource("native/windows-x86_64/libvips-42.dll")).rejects(hints);
        } else if (os.contains("win")) {
            assertThat(RuntimeHintsPredicates.resource().forResource("native/windows-x86_64/photolib-image.dll")).accepts(hints);
            assertThat(RuntimeHintsPredicates.resource().forResource("native/linux-x86_64/libvips-cpp.so.8.18.3")).rejects(hints);
        }
        assertThat(RuntimeHintsPredicates.resource().forResource("native/THIRD_PARTY_NOTICES.txt")).accepts(hints);
    }

    private static RuntimeHints collectHints(Class<?> annotatedClass) {
        ImportRuntimeHints annotation = AnnotatedElementUtils.findMergedAnnotation(annotatedClass, ImportRuntimeHints.class);
        assertThat(annotation).as("Expected @ImportRuntimeHints on %s", annotatedClass.getName()).isNotNull();
        RuntimeHints hints = new RuntimeHints();
        for (Class<? extends RuntimeHintsRegistrar> registrarClass : annotation.value()) {
            try {
                var ctor = registrarClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                ctor.newInstance().registerHints(hints, annotatedClass.getClassLoader());
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to invoke registrar " + registrarClass.getName(), ex);
            }
        }
        return hints;
    }
}
