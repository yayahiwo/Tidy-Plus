/**
 * Copyright 2023 Viacheslav Barkov
 * Copyright 2021 Microsoft Corporation.
 *
 * Parts of the following code are a derivative work of the code from the ONNX Runtime project,
 * which is licensed MIT.
 */

package com.slavabarkov.tidy

import android.graphics.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

const val DIM_BATCH_SIZE = 1
const val DIM_PIXEL_SIZE = 3
const val IMAGE_SIZE_X = 224
const val IMAGE_SIZE_Y = 224

fun allocateModelInputBuffer(): FloatBuffer {
    val bytes = DIM_BATCH_SIZE * DIM_PIXEL_SIZE * IMAGE_SIZE_X * IMAGE_SIZE_Y * 4
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
}

internal fun preProcessPixelsInto(
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
) {
    val stride = IMAGE_SIZE_X * IMAGE_SIZE_Y
    require(bmpData.size >= stride) { "bmpData must be at least $stride" }
    require(floats.size >= DIM_PIXEL_SIZE * stride) { "floats must be at least ${DIM_PIXEL_SIZE * stride}" }

    val inv255 = 1.0f / 255.0f
    val meanR = 0.48145467f
    val meanG = 0.4578275f
    val meanB = 0.40821072f
    val invStdR = 1.0f / 0.26862955f
    val invStdG = 1.0f / 0.2613026f
    val invStdB = 1.0f / 0.2757771f

    val stride2 = stride * 2
    for (idx in 0 until stride) {
        val pixelValue = bmpData[idx]
        val r = ((pixelValue shr 16) and 0xFF) * inv255
        val g = ((pixelValue shr 8) and 0xFF) * inv255
        val b = (pixelValue and 0xFF) * inv255
        floats[idx] = (r - meanR) * invStdR
        floats[idx + stride] = (g - meanG) * invStdG
        floats[idx + stride2] = (b - meanB) * invStdB
    }

    out.rewind()
    out.put(floats, 0, DIM_PIXEL_SIZE * stride)
    out.rewind()
}

fun preProcessInto(
    bitmap: Bitmap,
    bmpData: IntArray,
    floats: FloatArray,
    out: FloatBuffer,
) {
    bitmap.getPixels(bmpData, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    preProcessPixelsInto(bmpData, floats, out)
}

fun preProcess(bitmap: Bitmap): FloatBuffer {
    val stride = IMAGE_SIZE_X * IMAGE_SIZE_Y
    val bmpData = IntArray(stride)
    val floats = FloatArray(DIM_PIXEL_SIZE * stride)
    val out = allocateModelInputBuffer()
    preProcessInto(bitmap, bmpData, floats, out)
    return out
}

fun centerCrop(bitmap: Bitmap, imageSize: Int): Bitmap {
    val cropX: Int
    val cropY: Int
    val cropSize: Int
    if (bitmap.width >= bitmap.height) {
        cropX = bitmap.width / 2 - bitmap.height / 2
        cropY = 0
        cropSize = bitmap.height
    } else {
        cropX = 0
        cropY = bitmap.height / 2 - bitmap.width / 2
        cropSize = bitmap.width
    }
    var bitmapCropped = Bitmap.createBitmap(
        bitmap, cropX, cropY, cropSize, cropSize
    )
    bitmapCropped = Bitmap.createScaledBitmap(
        bitmapCropped, imageSize, imageSize, false
    )
    return bitmapCropped
}
