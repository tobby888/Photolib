package cn.photolib;

import com.aliyuncs.AcsError;
import com.aliyuncs.dm.model.v20151123.SingleSendMailResponse;
import com.aliyuncs.http.clients.ApacheHttpClient;
import com.aliyuncs.http.clients.CompatibleUrlConnClient;
import org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

final class PhotoLibRuntimeHints implements RuntimeHintsRegistrar {
    private static final List<String> POI_SCHEMA_PACKAGES = List.of(
            "org/openxmlformats/schemas/spreadsheetml/x2006",
            "org/openxmlformats/schemas/wordprocessingml/x2006",
            "org/openxmlformats/schemas/drawingml/x2006",
            "org/openxmlformats/schemas/officeDocument/x2006",
            "org/openxmlformats/schemas/xpackage/x2006"
    );

    private static final List<String> XMLBEANS_TYPE_SYSTEM_HOLDERS = List.of(
            "org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder",
            "org.apache.xmlbeans.metadata.system.sXMLCONFIG.TypeSystemHolder",
            "org.apache.xmlbeans.metadata.system.sXMLLANG.TypeSystemHolder",
            "org.apache.xmlbeans.metadata.system.sXMLSCHEMA.TypeSystemHolder",
            "org.apache.xmlbeans.metadata.system.sXMLTOOLS.TypeSystemHolder"
    );

    /**
     * 头像校验（UserAvatarValidator）、站点图标校验（BrandIconValidator）、GIF 首帧抽取（GifFirstFrame）
     * 以及 Apache POI autoSizeColumn 字体度量使用 AWT / Java2D / ImageIO / HarfBuzz，
     * 底层 libawt / libfontmanager / libjavajpeg 通过 JNI 访问以下类型。
     */
    private static final List<String> AWT_AND_IMAGEIO_JNI_TYPES = List.of(
            "java.lang.Boolean",
            "java.lang.String",
            "java.lang.System",
            "java.nio.Buffer",
            "java.nio.ByteBuffer",
            "com.sun.imageio.plugins.jpeg.JPEGImageReader",
            "com.sun.imageio.plugins.jpeg.JPEGImageWriter",
            "javax.imageio.plugins.jpeg.JPEGHuffmanTable",
            "javax.imageio.plugins.jpeg.JPEGQTable",
            "java.awt.AlphaComposite",
            "java.awt.Color",
            "java.awt.GraphicsEnvironment",
            "java.awt.geom.AffineTransform",
            "java.awt.geom.GeneralPath",
            "java.awt.geom.Path2D",
            "java.awt.geom.Path2D$Float",
            "java.awt.geom.Point2D$Float",
            "java.awt.geom.Rectangle2D$Float",
            "java.awt.image.BufferedImage",
            "java.awt.image.ColorModel",
            "java.awt.image.IndexColorModel",
            "java.awt.image.Raster",
            "java.awt.image.SampleModel",
            "java.awt.image.SinglePixelPackedSampleModel",
            "sun.awt.SunHints",
            "sun.awt.image.BufImgSurfaceData$ICMColorData",
            "sun.awt.image.ByteComponentRaster",
            "sun.awt.image.BytePackedRaster",
            "sun.awt.image.IntegerComponentRaster",
            "sun.font.CharToGlyphMapper",
            "sun.font.Font2D",
            "sun.font.FontStrike",
            "sun.font.FontUtilities",
            "sun.font.FreetypeFontScaler",
            "sun.font.GlyphList",
            "sun.font.PhysicalStrike",
            "sun.font.StrikeMetrics",
            "sun.font.TrueTypeFont",
            "sun.font.Type1Font",
            "sun.java2d.Disposer",
            "sun.java2d.InvalidPipeException",
            "sun.java2d.NullSurfaceData",
            "sun.java2d.SunGraphics2D",
            "sun.java2d.SurfaceData",
            "sun.java2d.loops.Blit",
            "sun.java2d.loops.BlitBg",
            "sun.java2d.loops.CompositeType",
            "sun.java2d.loops.DrawGlyphList",
            "sun.java2d.loops.DrawGlyphListAA",
            "sun.java2d.loops.DrawGlyphListLCD",
            "sun.java2d.loops.DrawLine",
            "sun.java2d.loops.DrawParallelogram",
            "sun.java2d.loops.DrawPath",
            "sun.java2d.loops.DrawPolygons",
            "sun.java2d.loops.DrawRect",
            "sun.java2d.loops.FillParallelogram",
            "sun.java2d.loops.FillPath",
            "sun.java2d.loops.FillRect",
            "sun.java2d.loops.FillSpans",
            "sun.java2d.loops.GraphicsPrimitive",
            "sun.java2d.loops.GraphicsPrimitiveMgr",
            "sun.java2d.loops.MaskBlit",
            "sun.java2d.loops.MaskFill",
            "sun.java2d.loops.ScaledBlit",
            "sun.java2d.loops.SurfaceType",
            "sun.java2d.loops.TransformHelper",
            "sun.java2d.loops.XORComposite",
            "sun.java2d.pipe.Region",
            "sun.java2d.pipe.RegionIterator"
    );

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources()
                .registerPattern("static/**")
                .registerPattern("db/migration/**")
                .registerPattern("mail/**");

        registerPoiAndXmlBeansHints(hints, classLoader);
        registerAliyunSdkHints(hints);
        registerMailHints(hints);
        registerFlywayHints(hints);
        registerAwtAndImageIoHints(hints);
    }

    /**
     * Apache POI (XSSFWorkbook / XWPFDocument) 依赖 XMLBeans 在运行时通过反射加载 schema 类型与 .xsb 元数据。
     */
    private static void registerPoiAndXmlBeansHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources()
                .registerPattern("org/apache/poi/schemas/ooxml/system/ooxml/**")
                .registerPattern("org/apache/xmlbeans/metadata/system/**")
                .registerResourceBundle("org.apache.xmlbeans.impl.regex.message");

        for (String holder : XMLBEANS_TYPE_SYSTEM_HOLDERS) {
            hints.reflection().registerType(TypeReference.of(holder),
                    MemberCategory.ACCESS_PUBLIC_FIELDS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
        // XWPFRun.addPicture -> XmlObjectBase._typedArray 通过反射实例化 CTPicture[] 数组。
        hints.reflection().registerType(CTPicture[].class);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        for (String pkg : POI_SCHEMA_PACKAGES) {
            registerClassPattern(hints, resolver, "classpath*:" + pkg + "/**/impl/*Impl.class",
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            registerClassPattern(hints, resolver, "classpath*:" + pkg + "/**/*$Enum.class",
                    MemberCategory.ACCESS_PUBLIC_FIELDS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
    }

    private static void registerClassPattern(RuntimeHints hints, PathMatchingResourcePatternResolver resolver,
                                             String pattern, MemberCategory... categories) {
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                String url = resource.getURL().toString();
                int marker = url.lastIndexOf("org/openxmlformats/schemas/");
                if (marker < 0 || !url.endsWith(".class")) continue;
                String className = url.substring(marker, url.length() - ".class".length()).replace('/', '.');
                hints.reflection().registerType(TypeReference.of(className), categories);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("扫描 Apache POI OOXML Schema 类失败: " + pattern, ex);
        }
    }

    /**
     * 阿里云 OSS SDK 初始化时会读取 ResourceBundle（oss / common）和 properties 资源；
     * DirectMail 客户端则依赖 endpoints.json / regions.txt 以及反射反序列化响应类型。
     */
    private static void registerAliyunSdkHints(RuntimeHints hints) {
        hints.resources()
                .registerResourceBundle("oss")
                .registerResourceBundle("common")
                .registerPattern("versioninfo.properties")
                .registerPattern("oss.mime.types")
                .registerPattern("endpoints.json")
                .registerPattern("project.properties")
                .registerPattern("regions.txt");

        hints.reflection().registerType(SingleSendMailResponse.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
        // DefaultAcsClient 解析非 2xx 错误响应时通过 AcsError.class.newInstance() 与 Gson 反射构造错误对象；
        // HttpClientFactory.buildClient() 则通过 Class.forName + 反射构造 HTTP 客户端实现。
        hints.reflection().registerType(AcsError.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
        hints.reflection().registerType(ApacheHttpClient.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(CompatibleUrlConnClient.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    /**
     * Jakarta Mail / Angus Mail 通过 ServiceLoader、META-INF/mailcap 与 Class.forName 动态加载
     * SMTP 传输实现、MIME 内容处理器、StreamProvider 与 SASL 认证器。
     * 显式补齐资源和反射提示，确保原生镜像里动态新建多组 SMTP 连接（SSL / STARTTLS / 明文、内嵌图片）与 JVM 一致。
     */
    private static void registerMailHints(RuntimeHints hints) {
        hints.resources()
                .registerPattern("META-INF/services/jakarta.mail.*")
                .registerPattern("META-INF/services/jakarta.mail.util.*")
                .registerPattern("META-INF/services/jakarta.activation.spi.*")
                .registerPattern("META-INF/mailcap")
                .registerPattern("META-INF/mailcap.default")
                .registerPattern("META-INF/jakarta.mailcap")
                .registerPattern("META-INF/jakarta.mailcap.default")
                .registerPattern("META-INF/mime.types")
                .registerPattern("META-INF/mimetypes.default")
                .registerPattern("META-INF/jakarta.mime.types")
                .registerPattern("META-INF/jakarta.mimetypes.default")
                .registerPattern("META-INF/javamail.providers")
                .registerPattern("META-INF/javamail.default.providers")
                .registerPattern("META-INF/jakarta.providers")
                .registerPattern("META-INF/jakarta.default.providers")
                .registerPattern("META-INF/javamail.address.map")
                .registerPattern("META-INF/javamail.default.address.map")
                .registerPattern("META-INF/jakarta.address.map")
                .registerPattern("META-INF/jakarta.default.address.map")
                .registerPattern("META-INF/javamail.charset.map")
                .registerPattern("META-INF/jakarta.charset.map");

        for (String typeName : List.of(
                "org.eclipse.angus.mail.smtp.SMTPProvider",
                "org.eclipse.angus.mail.smtp.SMTPSSLProvider",
                "org.eclipse.angus.mail.smtp.SMTPTransport",
                "org.eclipse.angus.mail.smtp.SMTPSSLTransport",
                "org.eclipse.angus.mail.smtp.SMTPSaslAuthenticator",
                "org.eclipse.angus.mail.util.MailStreamProvider",
                "org.eclipse.angus.activation.MailcapRegistryProviderImpl",
                "org.eclipse.angus.activation.MimeTypeRegistryProviderImpl",
                "org.eclipse.angus.mail.handlers.text_plain",
                "org.eclipse.angus.mail.handlers.text_html",
                "org.eclipse.angus.mail.handlers.text_xml",
                "org.eclipse.angus.mail.handlers.multipart_mixed",
                "org.eclipse.angus.mail.handlers.message_rfc822")) {
            hints.reflection().registerType(TypeReference.of(typeName),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }
    }

    /**
     * GraalVM 官方可达性元数据仓库仅内置 flyway-core / postgresql / sqlserver，不含 flyway-mysql；
     * 显式注册 MySQL / MariaDB DatabaseType 插件及其 ServiceLoader 声明，防止原生镜像启动迁移时丢失驱动插件。
     */
    private static void registerFlywayHints(RuntimeHints hints) {
        hints.resources().registerPattern("META-INF/services/org.flywaydb.core.extensibility.Plugin");
        hints.reflection().registerType(TypeReference.of("org.flywaydb.database.mysql.MySQLDatabaseType"),
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(TypeReference.of("org.flywaydb.database.mysql.mariadb.MariaDBDatabaseType"),
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
    }

    private static void registerAwtAndImageIoHints(RuntimeHints hints) {
        hints.resources().registerPattern("META-INF/services/javax.imageio.spi.*");
        hints.reflection().registerType(TypeReference.of("sun.java2d.marlin.DMarlinRenderingEngine"),
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(TypeReference.of("sun.font.HBShaper"),
                MemberCategory.INVOKE_DECLARED_METHODS);
        for (String typeName : AWT_AND_IMAGEIO_JNI_TYPES) {
            hints.jni().registerType(TypeReference.of(typeName),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
    }
}
