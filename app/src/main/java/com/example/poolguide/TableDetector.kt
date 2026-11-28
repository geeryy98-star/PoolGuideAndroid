
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

        val pockets = findPocketCandidates(bitmap, bounds)

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
        // The rendered 8 Ball Pool table does not always expose the exact
        // 2:1 playing-surface ratio: rails, pockets and screen crop change it.
        // Search a bounded range of plausible landscape table proportions.
        val minTableWidth = width / 3
        val maxTableWidth = width * 19 / 20
        val minX = width / 40
        val maxX = width * 39 / 40
        val minY = height / 40
        val maxY = height * 39 / 40
        val candidateStep = max(12, min(width, height) / 20)
        val lineStep = max(4, min(width, height) / 70)
        val aspectRatios = floatArrayOf(1.65f, 1.75f, 1.85f, 1.95f, 2.05f, 2.15f, 2.25f)

        val (horizontalNearby, verticalNearby) = buildEdgeNeighborhoods(edges, width, height)
        var bestScore = 0f
        var best: RectF? = null

        for (aspect in aspectRatios) {
            var tableWidth = minTableWidth
            while (tableWidth <= maxTableWidth) {
                val tableHeight = (tableWidth / aspect).toInt()
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
                        val topScore = edgeCoverage(horizontalNearby, width, height, left, top, right, top, lineStep)
                        val bottomScore = edgeCoverage(horizontalNearby, width, height, left, bottom, right, bottom, lineStep)
                        val leftScore = edgeCoverage(verticalNearby, width, height, left, top, left, bottom, lineStep)
                        val rightScore = edgeCoverage(verticalNearby, width, height, right, top, right, bottom, lineStep)
                        val weakest = min(min(topScore, bottomScore), min(leftScore, rightScore))
                        val average = (topScore + bottomScore + leftScore + rightScore) / 4f
                        // Reward four-sided support and penalize candidates
                        // explained by only one strong UI edge.
                        val score = average * 0.65f + weakest * 0.35f

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
        }

        return if (bestScore >= 0.13f) best else null
    }

    /**
     * Precompute edge proximity in the direction perpendicular to each rail.
     * This turns each sampled candidate border check into O(1), instead of
     * scanning a 2D tolerance square for every sample of every candidate.
     */
    private fun buildEdgeNeighborhoods(
        edges: BooleanArray,
        width: Int,
        height: Int
    ): Pair<BooleanArray, BooleanArray> {
        val tolerance = max(6, min(width, height) / 28)
        val horizontal = BooleanArray(edges.size)
        val vertical = BooleanArray(edges.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                var horizontalHit = false
                var verticalHit = false
                var offset = -tolerance
                while (offset <= tolerance && (!horizontalHit || !verticalHit)) {
                    if (!horizontalHit) {
                        val yy = y + offset
                        if (yy in 0 until height && edges[yy * width + x]) {
                            horizontalHit = true
                        }
                    }
                    if (!verticalHit) {
                        val xx = x + offset
                        if (xx in 0 until width && edges[y * width + xx]) {
                            verticalHit = true
                        }
                    }
                    offset++
                }
                horizontal[index] = horizontalHit
                vertical[index] = verticalHit
            }
        }
        return horizontal to vertical
    }

    /**
     * Fraction of samples along a candidate border that have a nearby edge.
     * The proximity masks are precomputed once per frame, keeping the search
     * fast enough for repeated live analysis on mid-range Android hardware.
     */
    private fun edgeCoverage(
        nearbyEdges: BooleanArray,
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
        var hits = 0
        for (i in 0..samples) {
            val fraction = i.toFloat() / samples
            val x = (x1 + dx * fraction).toInt()
            val y = (y1 + dy * fraction).toInt()
            if (x in 0 until width && y in 0 until height &&
                nearbyEdges[y * width + x]) {
                hits++
            }
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

    /**
     * Estimate pocket centers from the dark circular opening at the six
     * expected rail positions. Unlike the previous implementation, this does
     * not blindly return six synthetic points: each candidate must have a
     * dark interior and measurable contrast against its surrounding ring.
     */
    private fun findPocketCandidates(
        bitmap: Bitmap,
        bounds: RectF
    ): List<DetectedPocket> {
        val result = mutableListOf<DetectedPocket>()
        val imagePixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(imagePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val minDimension = min(bounds.width(), bounds.height())
        val diskRadius = max(3, (minDimension * 0.018f).toInt())
        val ringInner = diskRadius + 2
        val ringOuter = max(ringInner + 2, (minDimension * 0.052f).toInt())
        val searchRange = max(4, (minDimension * 0.045f).toInt())
        val searchStep = max(2, diskRadius / 2)

        data class PocketPosition(
            val x: Float,
            val y: Float,
            val xDirection: Int,
            val yDirection: Int
        )

        val positions = listOf(
            PocketPosition(bounds.left, bounds.top, 1, 1),
            PocketPosition(bounds.centerX(), bounds.top, 0, 1),
            PocketPosition(bounds.right, bounds.top, -1, 1),
            PocketPosition(bounds.left, bounds.bottom, 1, -1),
            PocketPosition(bounds.centerX(), bounds.bottom, 0, -1),
            PocketPosition(bounds.right, bounds.bottom, -1, -1)
        )

        fun luminance(x: Int, y: Int): Int {
            val color = imagePixels[y * bitmap.width + x]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            return (red * 299 + green * 587 + blue * 114) / 1000
        }

        for (position in positions) {
            var bestScore = 0f
            var bestDarkFraction = 0f
            var bestX = position.x.toInt()
            var bestY = position.y.toInt()

            val xOffsets = if (position.xDirection == 0) {
                (-searchRange..searchRange step searchStep).toList()
            } else {
                (0..searchRange step searchStep).map { it * position.xDirection }
            }
            val yOffsets = if (position.yDirection == 0) {
                (-searchRange..searchRange step searchStep).toList()
            } else {
                (0..searchRange step searchStep).map { it * position.yDirection }
            }

            for (dy in yOffsets) {
                for (dx in xOffsets) {
                    val cx = position.x.toInt() + dx
                    val cy = position.y.toInt() + dy
                    if (cx - ringOuter < 0 || cy - ringOuter < 0 ||
                        cx + ringOuter >= bitmap.width || cy + ringOuter >= bitmap.height) continue

                    var innerTotal = 0
                    var innerCount = 0
                    var darkCount = 0
                    var ringTotal = 0
                    var ringCount = 0

                    for (oy in -ringOuter..ringOuter) {
                        for (ox in -ringOuter..ringOuter) {
                            val distanceSquared = ox * ox + oy * oy
                            if (distanceSquared > ringOuter * ringOuter) continue
                            val value = luminance(cx + ox, cy + oy)
                            if (distanceSquared <= diskRadius * diskRadius) {
                                innerTotal += value
                                innerCount++
                                if (value < 85) darkCount++
                            } else if (distanceSquared >= ringInner * ringInner) {
                                ringTotal += value
                                ringCount++
                            }
                        }
                    }

                    if (innerCount == 0 || ringCount == 0) continue
                    val innerMean = innerTotal.toFloat() / innerCount
                    val ringMean = ringTotal.toFloat() / ringCount
                    val darkFraction = darkCount.toFloat() / innerCount
                    val contrast = ((ringMean - innerMean) / 110f).coerceIn(0f, 1f)
                    val score = darkFraction * 0.65f + contrast * 0.35f

                    if (score > bestScore) {
                        bestScore = score
                        bestDarkFraction = darkFraction
                        bestX = cx
                        bestY = cy
                    }
                }
            }

            // Reject uniform dark patches and bright rail highlights.
            if (bestScore >= 0.30f && bestDarkFraction >= 0.28f) {
                result.add(
                    DetectedPocket(
                        center = PoolPoint(bestX.toFloat(), bestY.toFloat()),
                        radius = diskRadius.toFloat() / bitmap.width,
                        confidence = bestScore.coerceIn(0f, 1f)
                    )
                )
            }
        }

        return result
    }

    private fun calculateConfidence(
        edges: BooleanArray,
        width: Int,
        bounds: RectF
    ): Float {

        val height = edges.size / width
        val step = max(3, min(width, height) / 90)
        val (horizontalNearby, verticalNearby) = buildEdgeNeighborhoods(edges, width, height)
        val left = bounds.left.toInt().coerceIn(0, width - 1)
        val right = bounds.right.toInt().coerceIn(0, width - 1)
        val top = bounds.top.toInt().coerceIn(0, height - 1)
        val bottom = bounds.bottom.toInt().coerceIn(0, height - 1)

        // Confidence is the mean coverage of all four rails, not just the
        // top and left edges. A table-shaped rectangle needs four supported sides.
        val coverage = listOf(
            edgeCoverage(horizontalNearby, width, height, left, top, right, top, step),
            edgeCoverage(horizontalNearby, width, height, left, bottom, right, bottom, step),
            edgeCoverage(verticalNearby, width, height, left, top, left, bottom, step),
            edgeCoverage(verticalNearby, width, height, right, top, right, bottom, step)
        )
        val weakestSide = coverage.minOrNull() ?: 0f
        val average = coverage.average().toFloat()
        return (average * 0.7f + weakestSide * 0.3f).coerceIn(0f, 1f)
    }
}
