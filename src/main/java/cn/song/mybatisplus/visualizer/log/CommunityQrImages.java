package cn.song.mybatisplus.visualizer.log;

import com.intellij.util.io.HttpRequests;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class CommunityQrImages {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_DIMENSION = 1024;
    private CommunityQrImages() {}

    public static BufferedImage fetch(String url) throws IOException {
        return HttpRequests.request(url).connectTimeout(5_000).readTimeout(5_000).gzip(false)
                .throwStatusCodeException(true).connect(request -> {
                    try (InputStream input = request.getInputStream()) { return decode(readBounded(input)); }
                });
    }

    public static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            int count = input.read(buffer);
            if (count < 0) break;
            if (output.size() + count > MAX_BYTES) throw new IOException("QR image exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) throw new IOException("Invalid QR image");
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported QR image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
                    throw new IOException("QR image dimensions exceed limit");
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Invalid QR image");
                return image;
            } finally { reader.dispose(); }
        }
    }

    public static BufferedImage scaled(BufferedImage image) {
        BufferedImage result = new BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        } finally { graphics.dispose(); }
        return result;
    }
}
