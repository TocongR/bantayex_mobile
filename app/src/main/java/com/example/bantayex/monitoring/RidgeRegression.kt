package com.example.bantayex.monitoring

import kotlin.math.abs

object RidgeRegression {

    fun fit(
        features: List<FloatArray>,
        targets: List<Float>,
        lambda: Float = 0.01f
    ): FloatArray {
        val n = features.size
        val m = features[0].size

        if (n < m) return FloatArray(m)

        val ata = Array(m) { DoubleArray(m) }
        val aty = DoubleArray(m)

        for (i in 0 until n) {
            val f = features[i]
            val y = targets[i].toDouble()
            for (r in 0 until m) {
                aty[r] += f[r] * y
                for (c in 0 until m) {
                    ata[r][c] += f[r].toDouble() * f[c].toDouble()
                }
            }
        }

        for (i in 0 until m) {
            ata[i][i] += lambda.toDouble()
        }

        return gaussianElimination(ata, aty, m) ?: FloatArray(m)
    }

    private fun gaussianElimination(
        a: Array<DoubleArray>,
        b: DoubleArray,
        m: Int
    ): FloatArray? {
        val aug = Array(m) { r ->
            DoubleArray(m + 1) { c -> if (c < m) a[r][c] else b[r] }
        }

        for (col in 0 until m) {
            var maxRow = col
            for (row in col + 1 until m) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
            }
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            if (abs(aug[col][col]) < 1e-12) return null

            for (row in col + 1 until m) {
                val factor = aug[row][col] / aug[col][col]
                for (c in col until m + 1) {
                    aug[row][c] -= factor * aug[col][c]
                }
            }
        }

        val x = DoubleArray(m)
        for (row in m - 1 downTo 0) {
            x[row] = aug[row][m]
            for (c in row + 1 until m) {
                x[row] -= aug[row][c] * x[c]
            }
            x[row] /= aug[row][row]
        }

        return FloatArray(m) { x[it].toFloat() }
    }
}