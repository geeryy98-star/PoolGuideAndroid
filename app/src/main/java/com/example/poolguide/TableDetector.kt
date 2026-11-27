
package com.example.poolguide

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DetectedPocket(
    val center: PoolPoint,
    val radius: Float,
    val confidence: Float
)

data class DetectedTable(
    val bounds: RectF,
    val pockets: List<DetectedPocket>,
    val confidence: Float
)

object TableDetector {

    private data class EdgePoint(
        val x: Int,
        val y: Int
    )

    private const val EDGE_THRESHOLD = 55f

    /**
     * Busca una mesa rectangular mediante sus bordes.
     * No depende del color del paño.
     *
     * El resultado es experimental: necesita calibración
     * con una captura real del juego.
     */
    fun detect(bitmap: Bitmap): DetectedTable? {

        if (bitmap.isRecycled) return null

        val width = bitmap.width
        val height = bitmap.height

        if (width < 100 || height < 100) return null

        val gray = createGrayImage(bitmap)

        val edges = findEdges(
            gray,
            width,
            height
        )

        // Android/MediaProjection puede entregar el fotograma en vertical
        // aunque el juego esté girado. En ese caso transponemos el mapa de
        // bordes para buscar la mesa con su lado largo en horizontal.
        val bounds = if (width >= height) {
            findLargestRectangle(edges, width, height)
        } else {
            val transposed = BooleanArray(edges.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    transposed[x * height + y] = edges[y * width + x]
                }
            }
            val rotatedBounds = findLargestRectangle(transposed, height, width)
            rotatedBounds?.let { rotated ->
                RectF(
                    rotated.top,
                    rotated.left,
                    rotated.bottom,
                    rotated.right
                )
            }
        } ?: return null

        val pockets = findPocketCandidates(
            edges,
            width,
            height,
            bounds
        )

        return DetectedTable(
            bounds = bounds,
            pockets = pockets,
            confidence = calculateConfidence(
                edges,
                width,
                bounds
            )
        )
    }

    private fun createGrayImage(
        bitmap: Bitmap
    ): FloatArray {

        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return FloatArray(pixels.size) { index ->

            val color = pixels[index]

            val red = (color shr 16) and 255
            val green = (color shr 8) and 255
            val blue = color and 255

            red * 0.299f +
                green * 0.587f +
                blue * 0.114f
        }
    }

    private fun findEdges(
        gray: FloatArray,
        width: Int,
        height: Int
    ): BooleanArray {

        val edges = BooleanArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {

                val index = y * width + x

                val gx =
                    -gray[index - width - 1] -
                    2f * gray[index - 1] -
                    gray[index + width - 1] +
                    gray[index - width + 1] +
                    2f * gray[index + 1] +
                    gray[index + width + 1]

                val gy =
                    -gray[index - width - 1] -
                    2f * gray[index - width] -
                    gray[index - width + 1] +
                    gray[index + width - 1] +
                    2f * gray[index + width] +
                    gray[index + width + 1]

                val magnitude = sqrt(gx * gx + gy * gy)

                edges[index] = magnitude > EDGE_THRESHOLD
            }
        }

        return edges
    }

    private fun findLargestRectangle(
        edges: BooleanArray,
        width: Int,
        height: Int
    ): RectF? {
        // The game table is approximately 2:1. Search a coarse grid rather
        // than exhaustively testing every possible rectangle on every frame.
        val minTableWidth = width / 3
        val maxTableWidth = width * 19 / 20
        val minX = width / 20
        val maxX = width * 19 / 20
        val minY = height / 20
        val maxY = height * 19 / 20
        // Coarse candidate grid keeps the live detector responsive on phones.
        // Edge pixels are now dense, so line samples need not align to a sparse grid.
        val candidateStep = max(12, min(width, height) / 18)
        val lineStep = max(3, min(width, height) / 90)

        var bestScore = 0f
        var best: RectF? = null

        var tableWidth = minTableWidth
        while (tableWidth <= maxTableWidth) {
            val tableHeight = tableWidth / 2
            if (tableHeight < height / 5 || tableHeight > height * 9 / 10) {
                tableWidth += candidateStep
                continue
            }

            var left = minX
            while (left + tableWidth <= maxX) {
                val right = left + tableWidth
                var top = minY
                while (top + tableHeight <= maxY) {
                    val bottom = top + tableHeight
                    val topScore = edgeCoverage(edges, width, height, left, top, right, top, lineStep)
                    val bottomScore = edgeCoverage(edges, width, height, left, bottom, right, bottom, lineStep)
                    val leftScore = edgeCoverage(edges, width, height, left, top, left, bottom, lineStep)
                    val rightScore = edgeCoverage(edges, width, height, right, top, right, bottom, lineStep)
                    // Each side is scored as a proportion of samples with an edge nearby.
                    // This tolerates anti-aliasing and small offsets in the coarse search grid.
                    val score = (topScore + bottomScore + leftScore + rightScore) / 4f

                    if (score > bestScore) {
                        bestScore = score
                        best = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                    }
                    top += candidateStep
                }
                left += candidateStep
            }
            tableWidth += candidateStep
        }

        return if (bestScore >= 0.16f) best else null
    }

    /**
     * Fraction of samples along a candidate border that have an edge within
     * a small neighbourhood. Exact-pixel sampling was too brittle: the search
     * grid often landed a few pixels beside the rendered table rail.
     */
    private fun edgeCoverage(
        edges: BooleanArray,
        width: Int,
        height: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        step: Int
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = sqrt((dx * dx + dy * dy).toFloat()).toInt()
        if (distance <= 0) return 0f
        val samples = max(1, distance / step)
        val tolerance = 3
        var hits = 0
        for (i in 0..samples) {
            val fraction = i.toFloat() / samples
            val x = (x1 + dx * fraction).toInt()
            val y = (y1 + dy * fraction).toInt()
            var found = false
            for (oy in -tolerance..tolerance) {
                for (ox in -tolerance..tolerance) {
                    val sx = x + ox
                    val sy = y + oy
                    if (sx in 0 until width && sy in 0 until height &&
                        edges[sy * width + sx]) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
            if (found) hits++
        }
        return hits.toFloat() / (samples + 1)
    }

    private fun edgeCount(
        edges: BooleanArray,
        width: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        step: Int
    ): Int {

        val dx = x2 - x1
        val dy = y2 - y1

        val distance = sqrt(
            (dx * dx + dy * dy).toFloat()
        ).toInt()

        if (distance <= 0) return 0

        val samples = max(1, distance / step)

        var count = 0

        for (i in 0..samples) {

            val fraction = i.toFloat() / samples

            val x = (x1 + dx * fraction).toInt()
            val y = (y1 + dy * fraction).toInt()

            if (
                x in 0 until width &&
                y in 0 until edges.size / width &&
                edges[y * width + x]
            ) {
                count++
            }
        }

        return count
    }

    private fun findPocketCandidates(
        edges: BooleanArray,
        width: Int,
        height: Int,
        bounds: RectF
    ): List<DetectedPocket> {

        val result = mutableListOf<DetectedPocket>()

        val positions = listOf(
            PoolPoint(0f, 0f),
            PoolPoint(0.5f, 0f),
            PoolPoint(1f, 0f),
            PoolPoint(0f, 1f),
            PoolPoint(0.5f, 1f),
            PoolPoint(1f, 1f)
        )

        for (position in positions) {

            val x = bounds.left +
                position.x * bounds.width()

            val y = bounds.top +
                position.y * bounds.height()

            val radius =
                min(bounds.width(), bounds.height()) * 0.035f

            val nearbyEdges = edgeCount(
                edges,
                width,
                (x - radius).toInt(),
                (y - radius).toInt(),
                (x + radius).toInt(),
                (y + radius).toInt(),
                max(2, radius.toInt() / 4)
            )

            val confidence = min(
                1f,
                nearbyEdges / 12f
            )

            result.add(
                DetectedPocket(
                    center = PoolPoint(
                        x,
                        y
                    ),
                    radius = radius / width,
                    confidence = confidence
                )
            )
        }

        return result
    }

    private fun calculateConfidence(
        edges: BooleanArray,
        width: Int,
        bounds: RectF
    ): Float {

        val height = edges.size / width
        val step = max(2, min(width, height) / 120)
        val left = bounds.left.toInt().coerceIn(0, width - 1)
        val right = bounds.right.toInt().coerceIn(0, width - 1)
        val top = bounds.top.toInt().coerceIn(0, height - 1)
        val bottom = bounds.bottom.toInt().coerceIn(0, height - 1)

        // Confidence is the mean coverage of all four rails, not just the
        // top and left edges. A table-shaped rectangle needs four supported sides.
        val coverage = listOf(
            edgeCoverage(edges, width, height, left, top, right, top, step),
            edgeCoverage(edges, width, height, left, bottom, right, bottom, step),
            edgeCoverage(edges, width, height, left, top, left, bottom, step),
            edgeCoverage(edges, width, height, right, top, right, bottom, step)
        )
        val weakestSide = coverage.minOrNull() ?: 0f
        val average = coverage.average().toFloat()
        return (average * 0.7f + weakestSide * 0.3f).coerceIn(0f, 1f)
    }
}
