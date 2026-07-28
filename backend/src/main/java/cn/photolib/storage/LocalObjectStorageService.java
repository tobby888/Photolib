package cn.photolib.storage;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.UploadSizeLimitExceededException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

public class LocalObjectStorageService implements ObjectStorageService {
    private static final String METADATA_SUFFIX = ".metadata";
    private static final String LEGACY_CONTENT_TYPE_SUFFIX = ".content-type";
    private static final String CONTENT_TYPE_PROPERTY = "content-type";
    private static final String USER_METADATA_PREFIX = "user.";

    private final Path root;
    private final String publicBaseUrl;
    private final byte[] signingSecret;

    public LocalObjectStorageService(StorageProperties properties) {
        root = Path.of(properties.localDirectory()).toAbsolutePath().normalize();
        publicBaseUrl = stripTrailingSlash(properties.publicBaseUrl());
        signingSecret = properties.signingSecret().getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建本地对象存储目录: " + root, ex);
        }
    }

    @Override
    public void initialize() {
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法初始化本地对象存储目录: " + root, ex);
        }
    }

    @Override
    public List<StoredObject> list(String prefix) {
        String effectivePrefix = prefix == null ? "" : prefix;
        Path walkRoot = listRoot(effectivePrefix);
        if (Files.notExists(walkRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(walkRoot)) {
            return paths.filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .filter(path -> !isMetadataSidecar(path))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.startsWith(".upload-") || !name.endsWith(".tmp");
                    })
                    .map(path -> new LocalObjectPath(path, objectKey(path)))
                    .filter(object -> object.objectKey().startsWith(effectivePrefix))
                    .map(object -> {
                        try {
                            return new StoredObject(object.objectKey(), Files.size(object.path()));
                        } catch (NoSuchFileException ex) {
                            return null;
                        } catch (IOException ex) {
                            throw new IllegalStateException("无法读取本地对象大小: " + object.objectKey(), ex);
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("无法枚举本地对象存储: " + walkRoot, ex);
        }
    }

    @Override
    public SignedUrl presignPut(String objectKey, String contentType, Duration ttl) {
        return signed(objectKey, null, "PUT", contentType, ttl);
    }

    @Override
    public SignedUrl presignGet(String objectKey, String downloadName, Duration ttl) {
        return signed(objectKey, downloadName, "GET", null, ttl);
    }

    @Override
    public ObjectInfo stat(String objectKey) {
        Path path = resolve(objectKey);
        try {
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("本地对象不存在: " + objectKey);
            StoredMetadata storedMetadata = readMetadata(path);
            String contentType = detectedImageContentType(path);
            if (contentType == null) {
                contentType = storedMetadata.contentType();
                if (contentType.isBlank()) contentType = "application/octet-stream";
            }
            return new ObjectInfo(Files.size(path), contentType, storedMetadata.userMetadata());
        } catch (IOException ex) {
            throw new IllegalStateException("读取本地对象失败: " + objectKey, ex);
        }
    }

    @Override
    public Optional<ObjectInfo> find(String objectKey) {
        Path path = resolve(objectKey);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException("本地对象不是普通文件: " + objectKey);
            }
            return Optional.of(stat(objectKey));
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new IllegalStateException("读取本地对象属性失败: " + objectKey, exception);
        } catch (IllegalArgumentException exception) {
            if (Files.notExists(path)) return Optional.empty();
            throw exception;
        } catch (IllegalStateException exception) {
            if (Files.notExists(path) || exception.getCause() instanceof NoSuchFileException) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException ex) {
            throw new IllegalStateException("打开本地对象失败: " + objectKey, ex);
        }
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType,
                    Map<String, String> userMetadata) {
        Path destination = resolve(objectKey);
        ObjectInfo objectInfo = new ObjectInfo(size, contentType, userMetadata);
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
            try {
                long copied = Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (size >= 0 && copied != size) {
                    throw new IllegalArgumentException("对象大小不匹配，期望 " + size + " 字节，实际 " + copied + " 字节");
                }
                Files.deleteIfExists(metadata(destination));
                Files.deleteIfExists(legacyContentTypeMetadata(destination));
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                writeMetadata(destination,
                        contentType == null ? "application/octet-stream" : contentType,
                        objectInfo.userMetadata());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            if (ex instanceof UploadSizeLimitExceededException) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, ex.getMessage());
            }
            throw new IllegalStateException("写入本地对象失败: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path path = resolve(objectKey);
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(metadata(path));
            Files.deleteIfExists(legacyContentTypeMetadata(path));
        } catch (IOException ex) {
            throw new IllegalStateException("删除本地对象失败: " + objectKey, ex);
        }
    }

    public Token resolveToken(String token, String expectedMethod) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\n", -1);
            if (parts.length != 6) throw new IllegalArgumentException("无效的本地对象签名");
            String payload = String.join("\n", parts[0], parts[1], parts[2], parts[3], parts[4]);
            if (!constantTimeEquals(sign(payload), parts[5])) {
                throw new IllegalArgumentException("本地对象签名校验失败");
            }
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[0]));
            if (expiresAt.isBefore(Instant.now())) throw new IllegalArgumentException("本地对象签名已过期");
            if (!expectedMethod.equals(parts[1])) throw new IllegalArgumentException("本地对象请求方法不匹配");
            return new Token(parts[2], parts[3].isBlank() ? null : parts[3],
                           parts[4].isBlank() ? null : parts[4], expiresAt);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无效的本地对象签名", ex);
        }
    }

    private SignedUrl signed(String objectKey, String downloadName, String method, String contentType, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        String name = downloadName == null ? "" : downloadName.replace("\n", "_");
        String safeKey = objectKey.replace("\n", "_");
        String safeContentType = contentType == null ? "" : contentType.replace("\n", "_");
        String payload = expiresAt.getEpochSecond() + "\n" + method + "\n" + safeKey + "\n" + name + "\n" + safeContentType;
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + "\n" + sign(payload)).getBytes(StandardCharsets.UTF_8));
        try {
            return new SignedUrl(new URL(publicBaseUrl + "/" + token), method, expiresAt);
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("本地对象存储公开地址无效: " + publicBaseUrl, ex);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() ||
            objectKey.contains("\0") || objectKey.contains("..") ||
            objectKey.startsWith("/") || objectKey.startsWith("\\")) {
            throw new IllegalArgumentException("非法对象路径");
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法对象路径");
        }
        return resolved;
    }

    private Path listRoot(String prefix) {
        if (prefix.contains("\0") || prefix.contains("..") || prefix.contains("\\")
                || prefix.startsWith("/") || prefix.startsWith("\\")) {
            throw new IllegalArgumentException("非法对象路径前缀");
        }
        int separator = prefix.lastIndexOf('/');
        if (separator < 0) {
            return root;
        }
        String directoryPrefix = prefix.substring(0, separator);
        if (directoryPrefix.isBlank()) {
            return root;
        }
        Path resolved = root.resolve(directoryPrefix).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法对象路径前缀");
        }
        return resolved;
    }

    private String objectKey(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("非法本地对象路径");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private Path metadata(Path object) {
        return object.resolveSibling(object.getFileName() + METADATA_SUFFIX);
    }

    private Path legacyContentTypeMetadata(Path object) {
        return object.resolveSibling(object.getFileName() + LEGACY_CONTENT_TYPE_SUFFIX);
    }

    private boolean isMetadataSidecar(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(METADATA_SUFFIX)
                || name.endsWith(LEGACY_CONTENT_TYPE_SUFFIX)
                || (name.startsWith(".metadata-") && name.endsWith(".tmp"));
    }

    private void writeMetadata(Path object, String contentType,
                               Map<String, String> userMetadata) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(CONTENT_TYPE_PROPERTY, contentType);
        userMetadata.forEach((key, value) ->
                properties.setProperty(USER_METADATA_PREFIX + key, value));

        Path sidecar = metadata(object);
        Path temporary = Files.createTempFile(object.getParent(), ".metadata-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, null);
            }
            Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private StoredMetadata readMetadata(Path object) throws IOException {
        Path sidecar = metadata(object);
        Map<String, String> userMetadata = new LinkedHashMap<>();
        String contentType = null;
        if (Files.isRegularFile(sidecar)) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            contentType = properties.getProperty(CONTENT_TYPE_PROPERTY);
            for (String name : properties.stringPropertyNames()) {
                if (name.startsWith(USER_METADATA_PREFIX)) {
                    userMetadata.put(name.substring(USER_METADATA_PREFIX.length()),
                            properties.getProperty(name));
                }
            }
        }
        if (contentType == null || contentType.isBlank()) {
            Path legacy = legacyContentTypeMetadata(object);
            contentType = Files.isRegularFile(legacy)
                    ? Files.readString(legacy, StandardCharsets.UTF_8).trim()
                    : "application/octet-stream";
        }
        return new StoredMetadata(contentType, userMetadata);
    }

    private String detectedImageContentType(Path object) throws IOException {
        byte[] signature = new byte[12];
        int length;
        try (InputStream input = Files.newInputStream(object)) {
            length = input.read(signature);
        }
        if (length >= 3 && (signature[0] & 0xff) == 0xff
                && (signature[1] & 0xff) == 0xd8 && (signature[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (length >= 8 && (signature[0] & 0xff) == 0x89
                && signature[1] == 0x50 && signature[2] == 0x4e && signature[3] == 0x47
                && signature[4] == 0x0d && signature[5] == 0x0a
                && signature[6] == 0x1a && signature[7] == 0x0a) {
            return "image/png";
        }
        if (length >= 12 && signature[0] == 0x52 && signature[1] == 0x49
                && signature[2] == 0x46 && signature[3] == 0x46
                && signature[8] == 0x57 && signature[9] == 0x45
                && signature[10] == 0x42 && signature[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成本地对象签名", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record Token(String objectKey, String downloadName, String contentType, Instant expiresAt) {
    }

    private record LocalObjectPath(Path path, String objectKey) {
    }

    private record StoredMetadata(String contentType, Map<String, String> userMetadata) {
    }
}
