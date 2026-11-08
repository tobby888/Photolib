package cn.photolib.photo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

/** 测试用的动图：多帧 GIF，以及在普通 PNG 里插一个 {@code acTL} 块的 APNG。 */
final class AnimatedFixtures {
    private AnimatedFixtures() {
    }

    /** 每帧一种纯色。 */
    static byte[] gif(int width, int height, Color... frames) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (Color color : frames) {
                BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) frame.setRGB(x, y, color.getRGB());
                }
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** 带噪点的 PNG，体积足够大，好让它超过成品目标体积。 */
    static byte[] noisyPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(76);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    /**
     * 在 IHDR 后面（或第一个 IDAT 后面）插一个 {@code acTL}。真正的 APNG 还有 fcTL / fdAT，
     * 但认动图只看 acTL，libvips 也只读 IDAT，这样就够了。
     */
    static byte[] withActl(byte[] png, int frames, boolean afterIdat) throws IOException {
        int insertAt = 8 + 8 + 13 + 4; // 签名 + IHDR
        if (afterIdat) {
            int offset = 8;
            while (true) {
                int length = readInt(png, offset);
                String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
                offset += 12 + length;
                if (type.equals("IDAT")) {
                    insertAt = offset;
                    break;
                }
            }
        }
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(chunk);
        byte[] type = "acTL".getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[8];
        writeInt(body, 0, frames);
        writeInt(body, 4, 0);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(body);
        data.writeInt(body.length);
        data.write(type);
        data.write(body);
        data.writeInt((int) crc.getValue());

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(png, 0, insertAt);
        result.write(chunk.toByteArray());
        result.write(png, insertAt, png.length - insertAt);
        return result.toByteArray();
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
