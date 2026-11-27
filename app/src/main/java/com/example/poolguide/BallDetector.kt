
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

        // A second, color/blob-based pass complements the circle-edge detector.
        // It is especially useful when antialiasing or ball stripes break the rim.
        candidates.addAll(
            detectColorBlobs(
                pixels = pixels,
                cropWidth = width,
                cropHeight = height,
                offsetX = left,
                offsetY = top,
                expectedRadius = estimatedRadius
            )
        )

        return suppressDuplicates(candidates, maxBalls)
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


    /**
     * Fast felt-background segmentation inspired by PoolAimAssist.
     * The dominant quantized table color is removed, then near-circular
     * connected components of approximately one ball diameter are retained.
     */
    private fun detectColorBlobs(
        pixels: IntArray,
        cropWidth: Int,
        cropHeight: Int,
        offsetX: Int,
        offsetY: Int,
        expectedRadius: Float
    ): List<DetectedBall> {
        val step = max(2, (expectedRadius / 3f).roundToInt())
        val cols = (cropWidth + step - 1) / step
        val rows = (cropHeight + step - 1) / step
        if (cols < 4 || rows < 4) return emptyList()

        val grid = IntArray(cols * rows)
        val buckets = HashMap<Int, Int>()
        for (gy in 0 until rows) {
            val y = min(cropHeight - 1, gy * step)
            for (gx in 0 until cols) {
                val x = min(cropWidth - 1, gx * step)
                val color = pixels[y * cropWidth + x]
                val index = gy * cols + gx
                grid[index] = color
                val red = ((color shr 16) and 255) / 16
                val green = ((color shr 8) and 255) / 16
                val blue = (color and 255) / 16
                val key = (red shl 8) or (green shl 4) or blue
                buckets[key] = (buckets[key] ?: 0) + 1
            }
        }

        val dominantKey = buckets.maxByOrNull { it.value }?.key ?: return emptyList()
        val feltR = ((dominantKey shr 8) and 15) * 16 + 8
        val feltG = ((dominantKey shr 4) and 15) * 16 + 8
        val feltB = (dominantKey and 15) * 16 + 8
        val foreground = BooleanArray(grid.size)
        val visited = BooleanArray(grid.size)
        for (i in grid.indices) {
            val color = grid[i]
            val dr = ((color shr 16) and 255) - feltR
            val dg = ((color shr 8) and 255) - feltG
            val db = (color and 255) - feltB
            foreground[i] = dr * dr + dg * dg + db * db > 48 * 48
        }

        val queue = IntArray(grid.size)
        val found = mutableListOf<DetectedBall>()
        val expectedDiameter = expectedRadius * 2f
        val minDiameter = expectedDiameter * 0.62f
        val maxDiameter = expectedDiameter * 1.48f

        for (start in grid.indices) {
            if (!foreground[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = cols
            var maxX = -1
            var minY = rows
            var maxY = -1
            var count = 0
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val current = queue[head++]
                val x = current % cols
                val y = current / cols
                count++
                sumX += x
                sumY += y
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)

                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until cols || ny !in 0 until rows) continue
                    val next = ny * cols + nx
                    if (foreground[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }

            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val diameterW = boxW * step.toFloat()
            val diameterH = boxH * step.toFloat()
            val diameter = max(diameterW, diameterH)
            val aspect = diameterW / max(1f, diameterH)
            val compactness = count.toFloat() / (boxW * boxH)
            if (diameter !in minDiameter..maxDiameter ||
                aspect !in 0.58f..1.72f ||
                compactness < 0.22f) continue

            val centerX = offsetX + ((minX + maxX + 1) * 0.5f * step)
            val centerY = offsetY + ((minY + maxY + 1) * 0.5f * step)
            val shapeScore = (1f - abs(1f - aspect)).coerceIn(0f, 1f)
            val confidence = (0.44f + compactness * 0.28f + shapeScore * 0.12f)
                .coerceIn(0.45f, 0.78f)
            found += DetectedBall(
                centerX = centerX,
                centerY = centerY,
                radius = diameter * 0.5f,
                confidence = confidence
            )
        }
        return found
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
