package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;

/**
 * Delegating {@link MediaCodecWrapper} that injects H.264 Constrained Baseline profile
 * and Level 3.1 into the {@link MediaFormat} before the real MediaCodec is configured.
 *
 * <p>Works around the gap in stream-webrtc-android 1.3.10 where
 * {@code HardwareVideoEncoder#initEncodeInternal} leaves the Baseline case empty
 * (lines 258-272), so MediaCodec falls back to its vendor default — High on every
 * Android phone tested. Tracked upstream at
 * https://issues.chromium.org/issues/40553774.
 */
final class BaselineMediaCodecWrapper implements MediaCodecWrapper {
    private static final String TAG = "BaselineWrapper";
    private static final String MIME_AVC = "video/avc";

    private final MediaCodecWrapper delegate;

    BaselineMediaCodecWrapper(MediaCodecWrapper delegate) {
        this.delegate = delegate;
    }

    @Override
    public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
        if (MIME_AVC.equals(format.getString(MediaFormat.KEY_MIME))) {
            format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            Log.i(TAG, "Forcing H.264 Constrained Baseline profile + Level 3.1, IDR every 1s: " + format);
        }
        delegate.configure(format, surface, crypto, flags);
    }

    @Override public void start() { delegate.start(); }
    @Override public void flush() { delegate.flush(); }
    @Override public void stop() { delegate.stop(); }
    @Override public void release() { delegate.release(); }
    @Override public int dequeueInputBuffer(long timeoutUs) { return delegate.dequeueInputBuffer(timeoutUs); }
    @Override public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
        delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
    }
    @Override public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
        return delegate.dequeueOutputBuffer(info, timeoutUs);
    }
    @Override public void releaseOutputBuffer(int index, boolean render) { delegate.releaseOutputBuffer(index, render); }
    @Override public MediaFormat getInputFormat() { return delegate.getInputFormat(); }
    @Override public MediaFormat getOutputFormat() { return delegate.getOutputFormat(); }
    @Override public MediaFormat getOutputFormat(int index) { return delegate.getOutputFormat(index); }
    @Override public ByteBuffer getInputBuffer(int index) { return delegate.getInputBuffer(index); }
    @Override public ByteBuffer getOutputBuffer(int index) { return delegate.getOutputBuffer(index); }
    @Override public Surface createInputSurface() { return delegate.createInputSurface(); }
    @Override public void setParameters(Bundle params) { delegate.setParameters(params); }
    @Override public MediaCodecInfo getCodecInfo() { return delegate.getCodecInfo(); }
}
