package cn.photolib.common.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lightweight JPEG/PNG magic-byte detection without decoding image pixels. */
public final class ImageSignature {
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private ImageSignature() {
    }

    public static String detect(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return detect(input);
        }
    }

    public static String detect(InputStream input) throws IOException {
        byte[] header = input.readNBytes(PNG.length);
        if (header.length >= 3
                && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (header.length >= PNG.length) {
            boolean matches = true;
            for (int index = 0; index < PNG.length; index++) {
                matches &= header[index] == PNG[index];
            }
            if (matches) return "image/png";
        }
        return null;
    }

    public static boolean matches(Path path, String declaredContentType) throws IOException {
        return declaredContentType != null && declaredContentType.equals(detect(path));
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
