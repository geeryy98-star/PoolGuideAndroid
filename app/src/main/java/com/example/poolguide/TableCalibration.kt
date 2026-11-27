package com.example.poolguide

import android.content.Context
import android.graphics.RectF

/**
 * User calibration in normalized landscape screen coordinates (0..1).
 * Four table corners followed by the six pocket centers.
 */
data class TableCalibration(
    val bounds: RectF,
    val pockets: List<PoolPoint>
) {
    companion object {
        private const val PREFS = "pool_guide_table_calibration"
        private const val KEY_POINTS = "points_v1"

        fun save(context: Context, taps: List<PoolPoint>) {
            require(taps.size == 10) { "La calibración necesita 10 puntos" }
            val corners = taps.take(4)
            val bounds = RectF(
                corners.minOf { it.x },
                corners.minOf { it.y },
                corners.maxOf { it.x },
                corners.maxOf { it.y }
            )
            require(bounds.width() > 0.15f && bounds.height() > 0.08f)
            val value = taps.joinToString(";") { "${it.x},${it.y}" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_POINTS, value).apply()
        }

        fun load(context: Context): TableCalibration? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_POINTS, null) ?: return null
            val points = raw.split(";").mapNotNull { item ->
                val pair = item.split(",")
                if (pair.size != 2) return@mapNotNull null
                val x = pair[0].toFloatOrNull() ?: return@mapNotNull null
                val y = pair[1].toFloatOrNull() ?: return@mapNotNull null
                if (!x.isFinite() || !y.isFinite()) return@mapNotNull null
                PoolPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            }
            if (points.size != 10) return null
            val corners = points.take(4)
            val bounds = RectF(
                corners.minOf { it.x }, corners.minOf { it.y },
                corners.maxOf { it.x }, corners.maxOf { it.y }
            )
            if (bounds.width() <= 0.15f || bounds.height() <= 0.08f) return null
            return TableCalibration(bounds, points.drop(4))
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_POINTS).apply()
        }
    }
}
