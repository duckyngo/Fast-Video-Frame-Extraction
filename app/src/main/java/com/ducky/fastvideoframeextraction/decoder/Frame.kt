package com.ducky.fastvideoframeextraction.decoder

import java.nio.ByteBuffer


/**
 * Created by Duc Ky Ngo on 9/15/2021.
 * duckyngo1705@gmail.com
 */
data class Frame(
    val byteBuffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val position: Int,
    val timestamp: Long,
    val rotation:Int,
    val isFlipX: Boolean,
    val isFlipY: Boolean)
