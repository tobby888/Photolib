package cn.photolib;

import cn.photolib.auth.mfa.WebAuthnSupport;
import cn.photolib.common.config.MybatisConfig;
import cn.photolib.notification.UserNotificationController;
import cn.photolib.notification.wecom.WeComConfig;
import cn.photolib.performance.PerformanceConfig;
import cn.photolib.photo.ImageCompressor;
import com.aliyuncs.dm.model.v20151123.SingleSendMailResponse;
import com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.authenticator.EdDSACOSEKey;
import com.webauthn4j.data.attestation.authenticator.RSACOSEKey;
import com.webauthn4j.data.client.CollectedClientData;
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
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(RuntimeHintsPredicates.resource().forBundle("oss")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forBundle("common")).accepts(hints);

        assertThat(RuntimeHintsPredicates.reflection().onType(CTPicture[].class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(SingleSendMailResponse.class)).accepts(hints);
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
        for (Class<?> ognlType : new Class<?>[]{SystemMetaObject.class, MetaObject.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(ognlType)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)).accepts(mybatisHints);
            assertThat(RuntimeHintsPredicates.reflection().onType(ognlType)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(mybatisHints);
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

        RuntimeHints webAuthnHints = collectHints(WebAuthnSupport.class);
        for (Class<?> type : new Class<?>[]{
                CollectedClientData.class, AttestationObject.class,
                EC2COSEKey.class, RSACOSEKey.class, EdDSACOSEKey.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)).accepts(webAuthnHints);
        }
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
