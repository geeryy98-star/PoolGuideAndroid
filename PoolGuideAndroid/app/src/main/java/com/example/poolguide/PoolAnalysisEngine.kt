
package com.example.poolguide

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot

data class PoolShotCandidate(
    val targetBallId: Int,
    val pocketId: Int,
    val plan: ShotPlan
)

data class PoolAnalysisResult(
    val detectedBalls: List<PoolBall>,
    val cueBall: PoolBall?,
    val candidates: List<PoolShotCandidate>,
    val message: String
)

object PoolAnalysisEngine {

    fun analyze(
        bitmap: Bitmap,
        tableBoundsPx: RectF,
        cueBallCenterPx: PoolPoint,
        pocketCentersPx: List<PoolPoint>,
        expectedRadiusPx: Float? = null,
        pocketCaptureRadiusPx: Float? = null,
        maxRebounds: Int = 2
    ): PoolAnalysisResult {

        if (bitmap.isRecycled) {
            return emptyResult("Imagen no disponible")
        }

        val tableWidth = tableBoundsPx.width()
        val tableHeight = tableBoundsPx.height()

        if (tableWidth <= 0f || tableHeight <= 0f) {
            return emptyResult("Limites de mesa incorrectos")
        }

        if (pocketCentersPx.size != 6) {
            return emptyResult("Se necesitan las 6 troneras")
        }

        val detected = BallDetector.detect(
            bitmap = bitmap,
            tableBounds = tableBoundsPx,
            expectedRadiusPx = expectedRadiusPx,
            maxBalls = 16
        )

        if (detected.isEmpty()) {
            return emptyResult("No se detectan bolas")
        }

        // Usamos el ancho de la mesa como unidad de referencia.
        // Asi los radios y las distancias mantienen la misma escala.
        fun normalize(point: PoolPoint): PoolPoint {
            return PoolPoint(
                (point.x - tableBoundsPx.left) / tableWidth,
                (point.y - tableBoundsPx.top) / tableWidth
            )
        }

        val cueDetection = detected.minByOrNull { ball ->
            hypot(
                ball.centerX - cueBallCenterPx.x,
                ball.centerY - cueBallCenterPx.y
            )
        } ?: return emptyResult("No se encuentra la bola blanca")

        val cueDistance = hypot(
            cueDetection.centerX - cueBallCenterPx.x,
            cueDetection.centerY - cueBallCenterPx.y
        )

        val cueTolerance = cueDetection.radius * 2.5f

        if (cueDistance > cueTolerance) {
            return emptyResult("No se identifica la bola blanca")
        }

        val balls = detected.mapIndexed { index, ball ->
            PoolBall(
                id = index,
                center = normalize(
                    PoolPoint(ball.centerX, ball.centerY)
                ),
                radius = ball.radius / tableWidth,
                isCueBall = ball === cueDetection
            )
        }

        val cueBall = balls.firstOrNull { it.isCueBall }
            ?: return emptyResult("Bola blanca no disponible")

        val objectBalls = balls.filter { !it.isCueBall }

        if (objectBalls.isEmpty()) {
            return PoolAnalysisResult(
                detectedBalls = balls,
                cueBall = cueBall,
                candidates = emptyList(),
                message = "No se detectan bolas objetivo"
            )
        }

        val table = PoolTableBounds(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = tableHeight / tableWidth
        )

        val captureRadius = (
            pocketCaptureRadiusPx
                ?: cueDetection.radius * 2.2f
            ) / tableWidth

        val pockets = pocketCentersPx.mapIndexed { index, center ->
            PoolPocket(
                id = index,
                center = normalize(center),
                captureRadius = captureRadius
            )
        }

        val candidates = mutableListOf<PoolShotCandidate>()

        for (target in objectBalls) {
            for (pocket in pockets) {

                val plan = TrajectoryCalculator.calculateShot(
                    cueBall = cueBall,
                    targetBall = target,
                    allBalls = balls,
                    table = table,
                    pocket = pocket,
                    maxRebounds = maxRebounds
                )

                if (plan != null) {
                    candidates.add(
                        PoolShotCandidate(
                            targetBallId = target.id,
                            pocketId = pocket.id,
                            plan = plan
                        )
                    )
                }
            }
        }

        return PoolAnalysisResult(
            detectedBalls = balls,
            cueBall = cueBall,
            candidates = candidates,
            message = if (candidates.isEmpty()) {
                "No se encuentran trayectorias"
            } else {
                "Analisis completado"
            }
        )
    }

    private fun emptyResult(message: String): PoolAnalysisResult {
        return PoolAnalysisResult(
            detectedBalls = emptyList(),
            cueBall = null,
            candidates = emptyList(),
            message = message
        )
    }
}
