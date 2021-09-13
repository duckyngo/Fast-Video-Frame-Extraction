package com.ducky.fastvideoframeextraction.decoder;

import java.nio.ByteBuffer;

/**
 * Created by Duc Ky Ngo on 9/13/2021.
 * duckyngo1705@gmail.com
 */
public interface IVideoFrameExtractor {
    void onCurrentFrameExtracted(ByteBuffer byteBuffer, int timestamp, FrameMetadata frameMetadata, boolean isFlipX, boolean isFlipY);

    void onAllFrameExtracted(int processedFrameCount, long processedTime);
}
