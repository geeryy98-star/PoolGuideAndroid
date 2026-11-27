
package com.example.poolguide

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectedBall(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val confidence: Float
)

object BallDetector {

    private const val MIN_CONFIDENCE = 0.42f
    private const val EDGE_THRESHOLD = 65f
    private const val CIRCLE_SAMPLES = 32

    /**
     * Busca posibles bolas mediante sus contornos circulares.
     *
     * tableBounds debe estar expresado en píxeles de la imagen.
     * expectedRadiusPx es opcional y permite ajustar el tamaño esperado.
     */
    fun detect(
        bitmap: Bitmap,
        tableBounds: RectF,
        expectedRadiusPx: Float? = null,
        maxBalls: Int = 16
    ): List<DetectedBall> {

        if (bitmap.isRecycled || maxBalls <= 0) {
            return emptyList()
        }

        val left = max(1, tableBounds.left.roundToInt())
        val top = max(1, tableBounds.top.roundToInt())
        val right = min(
            bitmap.width - 1,
            tableBounds.right.roundToInt()
        )
        val bottom = min(
            bitmap.height - 1,
            tableBounds.bottom.roundToInt()
        )

        val width = right - left
        val height = bottom - top

        if (width < 30 || height < 30) {
            return emptyList()
        }

        val pixels = IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            left,
            top,
            width,
            height
        )

        val gray = FloatArray(pixels.size)

        for (i in pixels.indices) {
            val color = pixels[i]

            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF

            gray[i] =
                red * 0.299f +
                green * 0.587f +
                blue * 0.114f
        }

        val edges = calculateEdges(gray, width, height)

        val estimatedRadius =
            expectedRadiusPx ?: (width * 0.012f)

        if (estimatedRadius <= 2f) {
            return emptyList()
        }

        val minRadius = max(
            3,
            (estimatedRadius * 0.72f).roundToInt()
        )

        val maxRadius = max(
            minRadius + 1,
            (estimatedRadius * 1.30f).roundToInt()
        )

        val radiusStep = max(
            1,
            (estimatedRadius * 0.07f).roundToInt()
        )

        val candidates = mutableListOf<DetectedBall>()

        val angles = FloatArray(CIRCLE_SAMPLES) {
            (2.0 * PI * it / CIRCLE_SAMPLES).toFloat()
        }

        var radius = minRadius

        while (radius <= maxRadius) {

            val centerStep = max(2, radius / 3)

            var cy = radius + 1

            while (cy < height - radius - 1) {

                var cx = radius + 1

                while (cx < width - radius - 1) {

                    val score = circleScore(
                        edges,
                        width,
                        cx,
                        cy,
                        radius,
                        angles
                    )

                    if (score >= MIN_CONFIDENCE) {

                        candidates.add(
                            DetectedBall(
                                centerX = left + cx.toFloat(),
                                centerY = top + cy.toFloat(),
                                radius = radius.toFloat(),
                                confidence = score
                            )
                        )
                    }

                    cx += centerStep
                }

                cy += centerStep
            }

            radius += radiusStep
        }

        return suppressDuplicates(
            candidates,
            maxBalls
        )
    }

    private fun calculateEdges(
        gray: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {

        val edges = FloatArray(gray.size)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {

                val i = y * width + x

                val gx =
                    -gray[i - width - 1] -
                    2f * gray[i - 1] -
                    gray[i + width - 1] +
                    gray[i - width + 1] +
                    2f * gray[i + 1] +
                    gray[i + width + 1]

                val gy =
                    -gray[i - width - 1] -
                    2f * gray[i - width] -
                    gray[i - width + 1] +
                    gray[i + width - 1] +
                    2f * gray[i + width] +
                    gray[i + width + 1]

                edges[i] = sqrt(gx * gx + gy * gy)
            }
        }

        return edges
    }

    private fun circleScore(
        edges: FloatArray,
        width: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
        angles: FloatArray
    ): Float {

        var hits = 0

        for (angle in angles) {

            val sampleX = centerX + (cos(angle) * radius).roundToInt()
            val sampleY = centerY + (sin(angle) * radius).roundToInt()
            val index = sampleY * width + sampleX
            val edgeFound =
                edges[index] >= EDGE_THRESHOLD ||
                edges[index - width] >= EDGE_THRESHOLD ||
                edges[index + width] >= EDGE_THRESHOLD ||
                edges[index - 1] >= EDGE_THRESHOLD ||
                edges[index + 1] >= EDGE_THRESHOLD
            if (edgeFound) hits++
        }

        return hits.toFloat() / angles.size
    }

    private fun suppressDuplicates(
        candidates: List<DetectedBall>,
        maxBalls: Int
    ): List<DetectedBall> {

        val sorted = candidates.sortedByDescending {
            it.confidence
        }

        val selected = mutableListOf<DetectedBall>()

        for (candidate in sorted) {

            val duplicate = selected.any { existing ->

                val dx =
                    candidate.centerX - existing.centerX

                val dy =
                    candidate.centerY - existing.centerY

                val distance = sqrt(dx * dx + dy * dy)

                distance < min(
                    candidate.radius,
                    existing.radius
                ) * 1.25f
            }

            if (!duplicate) {
                selected.add(candidate)
            }

            if (selected.size >= maxBalls) {
                break
            }
        }

        return selected
    }
}
