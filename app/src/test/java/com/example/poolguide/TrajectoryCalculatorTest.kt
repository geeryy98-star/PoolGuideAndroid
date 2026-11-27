package com.example.poolguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryCalculatorTest {

    private val table = PoolTableBounds(0f, 0f, 1f, 0.5f)

    @Test
    fun directShotReachesPocketBeforeCushion() {
        val cue = PoolBall(0, PoolPoint(0.20f, 0.25f), 0.02f, true)
        val target = PoolBall(1, PoolPoint(0.50f, 0.25f), 0.02f)
        val pocket = PoolPocket(0, PoolPoint(0.50f, 0f), 0.05f)

        val plan = TrajectoryCalculator.calculateShot(
            cueBall = cue,
            targetBall = target,
            allBalls = listOf(cue, target),
            table = table,
            pocket = pocket,
            maxRebounds = 2
        )

        assertNotNull(plan)
        assertTrue(plan!!.reachesPocket)
        assertTrue(plan.segments.size >= 2)
        assertEquals(PoolPoint(0.50f, 0f), plan.segments.last().end)
    }

    @Test
    fun ballAlreadyInsidePocketCaptureIsNotRejectedByCushion() {
        val cue = PoolBall(0, PoolPoint(0.20f, 0.25f), 0.02f, true)
        val target = PoolBall(1, PoolPoint(0.50f, 0.03f), 0.02f)
        val pocket = PoolPocket(0, PoolPoint(0.50f, 0f), 0.05f)

        val plan = TrajectoryCalculator.calculateShot(
            cueBall = cue,
            targetBall = target,
            allBalls = listOf(cue, target),
            table = table,
            pocket = pocket,
            maxRebounds = 2
        )

        assertNotNull(plan)
        assertTrue(plan!!.reachesPocket)
        assertEquals(PoolPoint(0.50f, 0f), plan.segments.last().end)
    }
}
