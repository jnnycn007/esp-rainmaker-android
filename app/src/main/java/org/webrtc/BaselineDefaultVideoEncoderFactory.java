package org.webrtc;

/**
 * Convenience subclass that wires {@link BaselineHardwareVideoEncoderFactory} as the
 * hardware encoder under {@link DefaultVideoEncoderFactory}, keeping the library's
 * built-in software encoder fallback. Placed in {@code org.webrtc} to reach the
 * package-private {@code DefaultVideoEncoderFactory(VideoEncoderFactory)} constructor.
 */
public final class BaselineDefaultVideoEncoderFactory extends DefaultVideoEncoderFactory {
    public BaselineDefaultVideoEncoderFactory(EglBase.Context eglContext,
                                              boolean enableIntelVp8Encoder,
                                              boolean enableH264HighProfile) {
        super(new BaselineHardwareVideoEncoderFactory(
            eglContext, enableIntelVp8Encoder, enableH264HighProfile));
    }
}
