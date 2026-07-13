package org.webrtc;

import java.io.IOException;

/**
 * {@link MediaCodecWrapperFactory} decorator that wraps every produced
 * {@link MediaCodecWrapper} with {@link BaselineMediaCodecWrapper} so H.264
 * configure calls get the Constrained Baseline profile injected.
 */
final class BaselineMediaCodecWrapperFactory implements MediaCodecWrapperFactory {
    private final MediaCodecWrapperFactory delegate;

    BaselineMediaCodecWrapperFactory(MediaCodecWrapperFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public MediaCodecWrapper createByCodecName(String name) throws IOException {
        return new BaselineMediaCodecWrapper(delegate.createByCodecName(name));
    }
}
