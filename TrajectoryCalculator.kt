
package com.example.poolguide

import kotlin.math.sqrt

/** Coordenadas normalizadas dentro de la superficie de juego (0..1). */
data class PoolPoint(val x: Float, val y: Float) {
    operator fun plus(other: PoolPoint) = PoolPoint(x + other.x, y + other.y)
    operator fun minus(other: PoolPoint) = PoolPoint(x - other.x, y - other.y)
    operator fun times(scale: Float) = PoolPoint(x * scale, y * scale)

    fun dot(other: PoolPoint): Float = x * other.x + y * other.y
    fun length(): Float = sqrt(x * x + y * y)

    fun normalized(): PoolPoint {
        val length = length()
        return if (length <= EPSILON) PoolPoint(0f, 0f)
        else this * (1f / length)
    }

    fun distanceTo(other: PoolPoint): Float = (this - other).length()

    companion object {
        private const val EPSILON = 0.000001f
    }
}

data class PoolBall(
    val id: Int,
    val center: PoolPoint,
    val radius: Float,
    val isCueBall: Boolean = false
)

data class PoolPocket(
    val id: Int,
    val center: PoolPoint,
    val captureRadius: Float
)

data class PoolTableBounds(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    init {
        require(right > left)
        require(bottom > top)
    }
}

enum class TrajectoryLineType {
    CUE_AIM,
    OBJECT_BALL,
    CUSHION_REBOUND
}

data class TrajectorySegment(
    val start: PoolPoint,
    val end: PoolPoint,
    val type: TrajectoryLineType
)

data class ShotPlan(
    val segments: List<TrajectorySegment>,
    val ghostBallPoint: PoolPoint,
    val targetPocketId: Int,
    val predictedRebounds: Int,
    val reachesPocket: Boolean,
    val blockedByBallId: Int? = null,
    val message: String
)

/**
 * Motor geométrico inicial.
 * Recibe bolas, mesa y troneras ya detectadas por los módulos de visión.
 */
object TrajectoryCalculator {

    private const val EPSILON = 0.0005f
    private const val MAX_TRACE_STEPS = 12

    fun calculateShot(
        cueBall: PoolBall,
        targetBall: PoolBall,
        allBalls: List<PoolBall>,
        table: PoolTableBounds,
        pocket: PoolPocket,
        maxRebounds: Int = 2
    ): ShotPlan? {

        if (cueBall.radius <= 0f || targetBall.radius <= 0f) return null
        if (cueBall.id == targetBall.id) return null

        val targetDirection =
            (pocket.center - targetBall.center).normalized()

        if (targetDirection.length() <= EPSILON) return null

        val contactDistance = cueBall.radius + targetBall.radius

        val ghostPoint =
            targetBall.center - targetDirection * contactDistance

        val cueDirection =
            (ghostPoint - cueBall.center).normalized()

        val cueDistance =
            cueBall.center.distanceTo(ghostPoint)

        if (cueDistance <= EPSILON) return null

        val cueBlocker = firstBallOnRay(
            start = cueBall.center,
            direction = cueDirection,
            maxDistance = cueDistance,
            balls = allBalls.filter {
                it.id != cueBall.id && it.id != targetBall.id
            },
            movingRadius = cueBall.radius
        )

        val segments = mutableListOf<TrajectorySegment>()

        segments += TrajectorySegment(
            cueBall.center,
            ghostPoint,
            TrajectoryLineType.CUE_AIM
        )

        if (cueBlocker != null) {
            return ShotPlan(
                segments,
                ghostPoint,
                pocket.id,
                0,
                false,
                cueBlocker.id,
                "Tiro bloqueado por otra bola"
            )
        }

        val trace = traceBall(
            start = targetBall.center,
            initialDirection = targetDirection,
            movingBall = targetBall,
            otherBalls = allBalls.filter {
                it.id != cueBall.id && it.id != targetBall.id
            },
            table = table,
            pockets = listOf(pocket),
            maxRebounds = maxRebounds
        )

        segments += trace.segments

        val message = when {
            trace.pocketed ->
                "Trayectoria calculada: llega a la tronera"

            trace.blockedByBallId != null ->
                "Trayectoria bloqueada por otra bola"

            trace.rebounds > 0 ->
                "Rebota ${trace.rebounds} veces; no llega a la tronera"

            else ->
                "Trayectoria directa no confirmada"
        }

        return ShotPlan(
            segments = segments,
            ghostBallPoint = ghostPoint,
            targetPocketId = pocket.id,
            predictedRebounds = trace.rebounds,
            reachesPocket = trace.pocketed,
            blockedByBallId = trace.blockedByBallId,
            message = message
        )
    }

    private data class TraceResult(
        val segments: List<TrajectorySegment>,
        val rebounds: Int,
        val pocketed: Boolean,
        val blockedByBallId: Int?
    )

    private fun traceBall(
        start: PoolPoint,
        initialDirection: PoolPoint,
        movingBall: PoolBall,
        otherBalls: List<PoolBall>,
        table: PoolTableBounds,
        pockets: List<PoolPocket>,
        maxRebounds: Int
    ): TraceResult {

        val segments = mutableListOf<TrajectorySegment>()

        var position = start
        var direction = initialDirection.normalized()
        var rebounds = 0

        repeat(MAX_TRACE_STEPS) {

            val wallHit = distanceToWall(
                position,
                direction,
                table,
                movingBall.radius
            )

            val pocketHit = nearestPocketHit(
                position,
                direction,
                pockets
            )

            val ballHit = firstBallOnRay(
                position,
                direction,
                wallHit.distance,
                otherBalls,
                movingBall.radius
            )

            if (
                pocketHit != null &&
                pocketHit.distance <= wallHit.distance &&
                (ballHit == null || pocketHit.distance <= ballHit.distance)
            ) {
                val end = position + direction * pocketHit.distance

                segments += TrajectorySegment(
                    position,
                    end,
                    TrajectoryLineType.OBJECT_BALL
                )

                return TraceResult(segments, rebounds, true, null)
            }

            if (
                ballHit != null &&
                ballHit.distance < wallHit.distance
            ) {
                val end = position + direction * ballHit.distance

                segments += TrajectorySegment(
                    position,
                    end,
                    TrajectoryLineType.OBJECT_BALL
                )

                return TraceResult(
                    segments,
                    rebounds,
                    false,
                    ballHit.id
                )
            }

            if (!wallHit.distance.isFinite() ||
                wallHit.distance <= EPSILON
            ) {
                return TraceResult(segments, rebounds, false, null)
            }

            val wallPoint =
                position + direction * wallHit.distance

            segments += TrajectorySegment(
                position,
                wallPoint,
                TrajectoryLineType.CUSHION_REBOUND
            )

            if (rebounds >= maxRebounds) {
                return TraceResult(segments, rebounds, false, null)
            }

            direction = when (wallHit.wall) {
                Wall.LEFT, Wall.RIGHT ->
                    PoolPoint(-direction.x, direction.y)

                Wall.TOP, Wall.BOTTOM ->
                    PoolPoint(direction.x, -direction.y)
            }

            position = wallPoint + direction * EPSILON
            rebounds++
        }

        return TraceResult(segments, rebounds, false, null)
    }

    private enum class Wall {
        LEFT, RIGHT, TOP, BOTTOM
    }

    private data class WallHit(
        val distance: Float,
        val wall: Wall
    )

    private data class CircleHit(
        val distance: Float,
        val id: Int
    )

    private data class PocketHit(
        val distance: Float
    )

    private fun distanceToWall(
        point: PoolPoint,
        direction: PoolPoint,
        table: PoolTableBounds,
        radius: Float
    ): WallHit {

        val left = table.left + radius
        val right = table.right - radius
        val top = table.top + radius
        val bottom = table.bottom - radius

        val candidates = mutableListOf<WallHit>()

        if (direction.x < -EPSILON) {
            candidates += WallHit(
                (left - point.x) / direction.x,
                Wall.LEFT
            )
        } else if (direction.x > EPSILON) {
            candidates += WallHit(
                (right - point.x) / direction.x,
                Wall.RIGHT
            )
        }

        if (direction.y < -EPSILON) {
            candidates += WallHit(
                (top - point.y) / direction.y,
                Wall.TOP
            )
        } else if (direction.y > EPSILON) {
            candidates += WallHit(
                (bottom - point.y) / direction.y,
                Wall.BOTTOM
            )
        }

        return candidates
            .filter {
                it.distance >= EPSILON && it.distance.isFinite()
            }
            .minByOrNull { it.distance }
            ?: WallHit(Float.POSITIVE_INFINITY, Wall.TOP)
    }

    private fun nearestPocketHit(
        start: PoolPoint,
        direction: PoolPoint,
        pockets: List<PoolPocket>
    ): PocketHit? {

        return pockets.mapNotNull { pocket ->

            val distance = rayCircleDistance(
                start,
                direction,
                pocket.center,
                pocket.captureRadius
            )

            if (distance != null) PocketHit(distance) else null

        }.minByOrNull { it.distance }
    }

    private fun firstBallOnRay(
        start: PoolPoint,
        direction: PoolPoint,
        maxDistance: Float,
        balls: List<PoolBall>,
        movingRadius: Float
    ): CircleHit? {

        return balls.mapNotNull { ball ->

            val distance = rayCircleDistance(
                start,
                direction,
                ball.center,
                movingRadius + ball.radius
            )

            if (
                distance != null &&
                distance < maxDistance - EPSILON
            ) {
                CircleHit(distance, ball.id)
            } else {
                null
            }

        }.minByOrNull { it.distance }
    }

    private fun rayCircleDistance(
        origin: PoolPoint,
        direction: PoolPoint,
        center: PoolPoint,
        radius: Float
    ): Float? {

        val offset = origin - center
        val b = offset.dot(direction)
        val c = offset.dot(offset) - radius * radius

        val discriminant = b * b - c

        if (discriminant < 0f) return null

        val root = sqrt(discriminant)

        val near = -b - root
        val far = -b + root

        return when {
            near > EPSILON -> near
            far > EPSILON -> far
            else -> null
        }
    }
}
