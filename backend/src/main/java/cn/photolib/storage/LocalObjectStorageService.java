package cn.photolib.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

public class LocalObjectStorageService implements ObjectStorageService {
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
    public SignedUrl presignPut(String objectKey, String contentType, Duration ttl) {
        return signed(objectKey, null, "PUT", ttl);
    }

    @Override
    public SignedUrl presignGet(String objectKey, String downloadName, Duration ttl) {
        return signed(objectKey, downloadName, "GET", ttl);
    }

    @Override
    public ObjectInfo stat(String objectKey) {
        Path path = resolve(objectKey);
        try {
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("本地对象不存在: " + objectKey);
            Path metadata = metadata(path);
            String contentType = Files.isRegularFile(metadata)
                    ? Files.readString(metadata, StandardCharsets.UTF_8)
                    : "application/octet-stream";
            return new ObjectInfo(Files.size(path), contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("读取本地对象失败: " + objectKey, ex);
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
    public void put(String objectKey, InputStream input, long size, String contentType) {
        Path destination = resolve(objectKey);
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
            try {
                long copied = Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (size >= 0 && copied != size) {
                    throw new IllegalArgumentException("对象大小不匹配，期望 " + size + " 字节，实际 " + copied + " 字节");
                }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                Files.writeString(metadata(destination),
                        contentType == null ? "application/octet-stream" : contentType,
                        StandardCharsets.UTF_8);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("写入本地对象失败: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path path = resolve(objectKey);
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(metadata(path));
        } catch (IOException ex) {
            throw new IllegalStateException("删除本地对象失败: " + objectKey, ex);
        }
    }

    public Token resolveToken(String token, String expectedMethod) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\n", -1);
            if (parts.length != 5) throw new IllegalArgumentException("无效的本地对象签名");
            String payload = String.join("\n", parts[0], parts[1], parts[2], parts[3]);
            if (!constantTimeEquals(sign(payload), parts[4])) {
                throw new IllegalArgumentException("本地对象签名校验失败");
            }
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[0]));
            if (expiresAt.isBefore(Instant.now())) throw new IllegalArgumentException("本地对象签名已过期");
            if (!expectedMethod.equals(parts[1])) throw new IllegalArgumentException("本地对象请求方法不匹配");
            return new Token(parts[2], parts[3].isBlank() ? null : parts[3], expiresAt);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无效的本地对象签名", ex);
        }
    }

    private SignedUrl signed(String objectKey, String downloadName, String method, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        String name = downloadName == null ? "" : downloadName.replace("\n", "_");
        String safeKey = objectKey.replace("\n", "_");
        String payload = expiresAt.getEpochSecond() + "\n" + method + "\n" + safeKey + "\n" + name;
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + "\n" + sign(payload)).getBytes(StandardCharsets.UTF_8));
        try {
            return new SignedUrl(new URL(publicBaseUrl + "/" + token), method, expiresAt);
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("本地对象存储公开地址无效: " + publicBaseUrl, ex);
        }
    }

    private Path resolve(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("非法对象路径");
        return resolved;
    }

    private Path metadata(Path object) {
        return object.resolveSibling(object.getFileName() + ".content-type");
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

    public record Token(String objectKey, String downloadName, Instant expiresAt) {
    }
}
