package cn.photolib.photo;

import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 把 GIF 的第一帧画到逻辑画布上，存成 PNG，交给原生层照常编码成 WebP 缩略图。
 *
 * <p>原生层不认 GIF；为一张静态首帧去改 Zig/C 并重编原生库不值得。JDK 自带 GIF 解码器，
 * 画布尺寸在调用前已经由 {@link AnimatedImages#inspectGif} 卡在 4096×4096 以内，
 * 这里整幅解码的堆开销因此有上限。</p>
 *
 * <p>首帧可能比画布小、带偏移（只画了变化的那一块），所以按图像描述符里的位置贴到透明画布上，
 * 而不是直接用解码出来的那一块。</p>
 */
final class GifFirstFrame {
    private static final String GIF_IMAGE_METADATA = "javax_imageio_gif_image_1.0";

    private GifFirstFrame() {
    }

    static void writePng(Path gif, AnimatedImages.Info info, Path png) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("JDK 缺少 GIF 解码器");
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(gif.toFile())) {
            if (input == null) throw new IOException("无法读取 GIF 文件");
            reader.setInput(input, true, false);
            BufferedImage frame = reader.read(0);
            int[] offset = frameOffset(reader.getImageMetadata(0));
            BufferedImage canvas = new BufferedImage(info.width(), info.height(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.drawImage(frame, offset[0], offset[1], null);
            } finally {
                graphics.dispose();
            }
            try (var output = Files.newOutputStream(png)) {
                if (!ImageIO.write(canvas, "png", output)) {
                    throw new IOException("JDK 缺少 PNG 编码器");
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // JDK 的 GIF 解码器遇到坏数据会抛各种运行时异常（数组越界等）。
            throw new IOException("GIF 首帧解码失败", exception);
        } finally {
            reader.dispose();
        }
    }

    private static int[] frameOffset(IIOMetadata metadata) {
        if (metadata == null) return new int[] {0, 0};
        try {
            Node root = metadata.getAsTree(GIF_IMAGE_METADATA);
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!"ImageDescriptor".equals(child.getNodeName())) continue;
                Node left = child.getAttributes().getNamedItem("imageLeftPosition");
                Node top = child.getAttributes().getNamedItem("imageTopPosition");
                return new int[] {
                        left == null ? 0 : Integer.parseInt(left.getNodeValue()),
                        top == null ? 0 : Integer.parseInt(top.getNodeValue())
                };
            }
        } catch (IllegalArgumentException ignored) {
            // 元数据格式对不上时按左上角贴，最坏是首帧位置偏一点。
        }
        return new int[] {0, 0};
    }
}
