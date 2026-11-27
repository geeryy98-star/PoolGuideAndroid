package com.example.poolguide

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.Image
import kotlin.math.max

data class GuideFrameAnalysis(
    val message: String,
    val screenLines: FloatArray = floatArrayOf(),
    val screenWidth: Int,
    val screenHeight: Int
)

/**
 * First live-image analysis pass. Coordinates returned in this object are
 * expressed in the original MediaProjection frame, not in the downscaled bitmap.
 */
object LivePoolAnalyzer {
    fun analyze(image: Image): GuideFrameAnalysis {
        val width = image.width
        val height = image.height
        val plane = image.planes.firstOrNull()
            ?: return GuideFrameAnalysis("Imagen sin datos", floatArrayOf(), width, height)
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val index = row + x * pixelStride
                if (index + 2 >= buffer.limit()) continue
                val red = buffer.get(index).toInt() and 0xFF
                val green = buffer.get(index + 1).toInt() and 0xFF
                val blue = buffer.get(index + 2).toInt() and 0xFF
                pixels[y * width + x] = (0xFF shl 24) or
                    (red shl 16) or (green shl 8) or blue
            }
        }

        val fullBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fullBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val maxDimension = 640
        val scale = minOf(1f, maxDimension.toFloat() / max(width, height))
        val scaledWidth = max(1, (width * scale).toInt())
        val scaledHeight = max(1, (height * scale).toInt())
        val bitmap = if (scaledWidth == width && scaledHeight == height) fullBitmap
            else Bitmap.createScaledBitmap(fullBitmap, scaledWidth, scaledHeight, true)
        if (bitmap !== fullBitmap) fullBitmap.recycle()

        try {
            val table = TableDetector.detect(bitmap)
                ?: return GuideFrameAnalysis("Buscando mesa de billar…", floatArrayOf(), width, height)

            if (table.confidence < 0.08f || table.pockets.size != 6) {
                return GuideFrameAnalysis("Mesa detectada con poca confianza; ajustando…", floatArrayOf(), width, height)
            }

            val expectedRadius = table.bounds.width() * 0.012f
            val balls = BallDetector.detect(
                bitmap = bitmap,
                tableBounds = table.bounds,
                expectedRadiusPx = expectedRadius,
                maxBalls = 16
            )
            if (balls.isEmpty()) {
                return GuideFrameAnalysis("Mesa localizada; buscando bolas…", floatArrayOf(), width, height)
            }

            val cue = balls.maxByOrNull { ball ->
                val x = ball.centerX.toInt().coerceIn(0, bitmap.width - 1)
                val y = ball.centerY.toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                val brightness = (red + green + blue) / 3
                val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
                brightness - saturation * 0.65f
            } ?: return GuideFrameAnalysis("No se identifica la bola blanca", floatArrayOf(), width, height)

            val color = bitmap.getPixel(
                cue.centerX.toInt().coerceIn(0, bitmap.width - 1),
                cue.centerY.toInt().coerceIn(0, bitmap.height - 1)
            )
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            val brightness = (red + green + blue) / 3
            val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
            if (brightness < 150 || saturation > 95) {
                return GuideFrameAnalysis("Mesa y bolas detectadas; no se confirma la blanca", floatArrayOf(), width, height)
            }

            val factorX = width.toFloat() / bitmap.width
            val factorY = height.toFloat() / bitmap.height
            val bounds = RectF(table.bounds)
            val pocketCenters = table.pockets.map { it.center }
            val result = PoolAnalysisEngine.analyze(
                bitmap = bitmap,
                tableBoundsPx = bounds,
                cueBallCenterPx = cue.centerX.let { PoolPoint(it, cue.centerY) },
                pocketCentersPx = pocketCenters,
                expectedRadiusPx = expectedRadius,
                pocketCaptureRadiusPx = cue.radius * 2.2f,
                maxRebounds = 2,
                preDetectedBalls = balls
            )
            val best = result.candidates
                .sortedWith(
                    compareBy<PoolShotCandidate> { if (it.plan.reachesPocket) 0 else 1 }
                        .thenBy { it.plan.predictedRebounds }
                        .thenBy { candidate ->
                            candidate.plan.segments.sumOf { segment ->
                                segment.start.distanceTo(segment.end).toDouble()
                            }
                        }
                )
                .firstOrNull()
                ?: return GuideFrameAnalysis(result.message, floatArrayOf(), width, height)

            val tableWidth = table.bounds.width()
            val lines = best.plan.segments.flatMap { segment ->
                listOf(
                    (table.bounds.left + segment.start.x * tableWidth) * factorX,
                    (table.bounds.top + segment.start.y * tableWidth) * factorY,
                    (table.bounds.left + segment.end.x * tableWidth) * factorX,
                    (table.bounds.top + segment.end.y * tableWidth) * factorY
                )
            }.toFloatArray()

            val status = if (best.plan.reachesPocket) {
                "Trayectoria candidata calculada"
            } else {
                "Trayectoria estimada; tronera no confirmada"
            }
            return GuideFrameAnalysis(status, lines, width, height)
        } catch (error: Exception) {
            return GuideFrameAnalysis("Análisis: ${error.javaClass.simpleName}", floatArrayOf(), width, height)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
