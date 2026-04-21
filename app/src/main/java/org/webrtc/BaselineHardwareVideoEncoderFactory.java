package org.webrtc;

import android.util.Log;
import androidx.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * {@link HardwareVideoEncoderFactory} subclass that forces H.264 encoders to emit
 * Constrained Baseline bitstream by swapping the {@link MediaCodecWrapperFactory}
 * field of the produced {@link HardwareVideoEncoder} with a
 * {@link BaselineMediaCodecWrapperFactory} wrapper.
 *
 * <p>Addresses Chromium issue <a href="https://issues.chromium.org/issues/40553774">40553774</a>:
 * stream-webrtc-android 1.3.10's {@code HardwareVideoEncoder#initEncodeInternal} leaves the
 * Baseline case at line 268-269 empty, so MediaCodec picks a vendor default (High) even when
 * the SDP says Baseline. Needed by the ESP32-P4 tinyh264 decoder, which is Baseline-only.
 *
 * <p>For non-H264 codecs (VP8/VP9/AV1) the parent behavior is preserved untouched.
 */
public class BaselineHardwareVideoEncoderFactory extends HardwareVideoEncoderFactory {
    private static final String TAG = "BaselineHWEncFactory";
    private static final String FIELD_NAME = "mediaCodecWrapperFactory";

    public BaselineHardwareVideoEncoderFactory(EglBase.Context sharedContext,
                                               boolean enableIntelVp8Encoder,
                                               boolean enableH264HighProfile) {
        super(sharedContext, enableIntelVp8Encoder, enableH264HighProfile);
    }

    @Nullable
    @Override
    public VideoEncoder createEncoder(VideoCodecInfo input) {
        VideoEncoder encoder = super.createEncoder(input);
        if (encoder instanceof HardwareVideoEncoder && "H264".equalsIgnoreCase(input.name)) {
            injectBaselineWrapper((HardwareVideoEncoder) encoder);
        }
        return encoder;
    }

    private static void injectBaselineWrapper(HardwareVideoEncoder encoder) {
        try {
            Field field = HardwareVideoEncoder.class.getDeclaredField(FIELD_NAME);
            field.setAccessible(true);
            Object current = field.get(encoder);
            if (current instanceof MediaCodecWrapperFactory
                && !(current instanceof BaselineMediaCodecWrapperFactory)) {
                field.set(encoder, new BaselineMediaCodecWrapperFactory((MediaCodecWrapperFactory) current));
                Log.i(TAG, "H.264 encoder patched: MediaCodecWrapperFactory wrapped with BaselineMediaCodecWrapperFactory");
            }
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Could not patch H.264 encoder; bitstream may be emitted as High profile", e);
        }
    }
}
