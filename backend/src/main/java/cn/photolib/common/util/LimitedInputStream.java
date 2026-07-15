package cn.photolib.common.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Stops streaming uploads before they can exceed their endpoint limit. */
public final class LimitedInputStream extends FilterInputStream {
    private final long maximum;
    private long count;

    public LimitedInputStream(InputStream input, long maximum) {
        super(input);
        this.maximum = maximum;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) increment(1);
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int read = super.read(bytes, offset, length);
        if (read > 0) increment(read);
        return read;
    }

    private void increment(long amount) throws IOException {
        count += amount;
        if (count > maximum) throw new UploadSizeLimitExceededException();
    }
}
