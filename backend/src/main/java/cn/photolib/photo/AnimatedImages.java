package cn.photolib.photo;

import cn.photolib.common.upload.UnprocessableImageException;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 认出动图：只按文件结构数帧，不解码像素。
 *
 * <p>动图（GIF，以及带 {@code acTL} 块的 APNG）的成品图原样保存、不做质量压缩，只生成静态
 * 首帧缩略图。压缩器只会产出单帧图片，把一张动图送进去等于悄悄删掉第一帧以外的全部内容。
 * GIF 不论几帧都原样保存：压缩器本来就不会写 GIF。</p>
 */
public final class AnimatedImages {
    public static final String GIF = "image/gif";
    public static final String PNG = "image/png";

    /**
     * GIF 首帧要在 Java 堆里整幅解码（见 {@link GifFirstFrame}），按 4 字节/像素算，
     * 这个上限是 64 MiB。正常的动图远小于它；更大的画布多半是构造出来的。
     */
    static final long MAX_GIF_PIXELS = 4096L * 4096L;
    static final int MAX_GIF_DIMENSION = 16_384;

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private AnimatedImages() {
    }

    /**
     * @param frames 数到的帧数（APNG 取 {@code acTL} 里声明的帧数）
     * @param width  GIF 的逻辑画布宽度；PNG 为 0，尺寸照旧由原生层读
     */
    public record Info(boolean gif, boolean animated, int frames, int width, int height) {
        static final Info STILL = new Info(false, false, 1, 0, 0);

        /** 成品图要不要原样保存。 */
        public boolean keepAsIs() {
            return gif || animated;
        }
    }

    public static Info inspect(Path source, String contentType) throws IOException {
        if (GIF.equals(contentType)) return inspectGif(source);
        if (PNG.equals(contentType)) return inspectPng(source);
        return Info.STILL;
    }

    /**
     * 按 GIF89a 的块结构走一遍：扩展块和图像块都由若干长度前缀的子块组成，以长度 0 结束。
     * 每遇到一个图像描述符（0x2C）算一帧。文件在中途截断时按已经数到的帧数算，
     * 与浏览器的容忍程度一致；连一帧都没有才算坏图。
     */
    static Info inspectGif(Path source) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source), 64 * 1024)) {
            byte[] header = input.readNBytes(6);
            String signature = new String(header, StandardCharsets.US_ASCII);
            if (!signature.equals("GIF87a") && !signature.equals("GIF89a")) {
                throw new IOException("GIF 文件头无效");
            }
            int width = readU16le(input);
            int height = readU16le(input);
            int packed = readU8(input);
            skip(input, 2);
            requireSafeGifSize(width, height);
            if ((packed & 0x80) != 0) skip(input, 3L * (1 << ((packed & 0x07) + 1)));

            int frames = 0;
            try {
                while (true) {
                    int block = input.read();
                    if (block < 0 || block == 0x3B) break;
                    if (block == 0x21) {
                        readU8(input);
                        skipSubBlocks(input);
                    } else if (block == 0x2C) {
                        skip(input, 4);
                        int frameWidth = readU16le(input);
                        int frameHeight = readU16le(input);
                        int framePacked = readU8(input);
                        requireSafeGifSize(frameWidth, frameHeight);
                        if ((framePacked & 0x80) != 0) {
                            skip(input, 3L * (1 << ((framePacked & 0x07) + 1)));
                        }
                        readU8(input);
                        skipSubBlocks(input);
                        frames++;
                    } else {
                        break;
                    }
                }
            } catch (EOFException truncated) {
                // 截断的尾巴：已经数到的帧照样算。
            }
            if (frames == 0) throw new IOException("GIF 里没有图像帧");
            return new Info(true, frames > 1, frames, width, height);
        } catch (EOFException truncated) {
            throw new IOException("GIF 文件不完整", truncated);
        }
    }

    /**
     * APNG 的 {@code acTL} 块按规范必须出现在第一个 {@code IDAT} 之前，所以走到 IDAT
     * 就可以停，不用读完整个文件。{@code acTL} 声明一帧的不算动图。
     */
    static Info inspectPng(Path source) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source), 8 * 1024)) {
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
            if (!java.util.Arrays.equals(signature, PNG_SIGNATURE)) {
                throw new IOException("PNG 文件头无效");
            }
            while (true) {
                long length = readU32be(input);
                String type = new String(input.readNBytes(4), StandardCharsets.US_ASCII);
                if (type.length() < 4) return Info.STILL;
                if (type.equals("acTL")) {
                    if (length < 8) return Info.STILL;
                    long frames = readU32be(input);
                    boolean animated = frames > 1;
                    int count = (int) Math.min(frames, Integer.MAX_VALUE);
                    return new Info(false, animated, Math.max(count, 1), 0, 0);
                }
                if (type.equals("IDAT") || type.equals("IEND")) return Info.STILL;
                skip(input, length + 4);
            }
        } catch (EOFException truncated) {
            return Info.STILL;
        }
    }

    private static void requireSafeGifSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("GIF 画布尺寸无效");
        if (width > MAX_GIF_DIMENSION || height > MAX_GIF_DIMENSION
                || (long) width * height > MAX_GIF_PIXELS) {
            throw new UnprocessableImageException(GIF_TOO_LARGE,
                    "GIF canvas " + width + "x" + height);
        }
    }

    static final String GIF_TOO_LARGE =
            "这张 GIF 的画面尺寸太大（单帧不能超过 4096×4096 像素），请缩小后重新上传。";

    private static void skipSubBlocks(InputStream input) throws IOException {
        while (true) {
            int size = readU8(input);
            if (size == 0) return;
            skip(input, size);
        }
    }

    private static int readU8(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static int readU16le(InputStream input) throws IOException {
        return readU8(input) | (readU8(input) << 8);
    }

    private static long readU32be(InputStream input) throws IOException {
        return ((long) readU8(input) << 24) | ((long) readU8(input) << 16)
                | ((long) readU8(input) << 8) | readU8(input);
    }

    private static void skip(InputStream input, long count) throws IOException {
        input.skipNBytes(count);
    }
}
