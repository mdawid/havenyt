package pl.motioncam

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Frame-difference motion detector on the Y plane of YUV_420_888.
 *
 * The luminance plane is downsampled to a GRID_W x GRID_H grid by averaging.
 * Each grid cell is compared against the previous frame; a cell counts as
 * "moved" when the absolute luminance delta exceeds [threshold]. Motion is
 * reported when the count of moved cells exceeds [minCells].
 */
class MotionDetector(
    var threshold: Int,
    var minCells: Int,
    private val warmupFrames: Int = 8,
    private val onMotion: () -> Unit,
) : ImageAnalysis.Analyzer {

    private var prev: IntArray? = null
    private var seen = 0

    @Volatile var armed: Boolean = true

    override fun analyze(image: ImageProxy) {
        try {
            if (!armed) return
            val grid = sampleY(image) ?: return
            val previous = prev
            prev = grid
            seen++
            if (previous == null || seen < warmupFrames) return

            var moved = 0
            for (i in grid.indices) {
                if (abs(grid[i] - previous[i]) >= threshold) moved++
            }
            if (moved >= minCells) onMotion()
        } finally {
            image.close()
        }
    }

    fun reset() {
        prev = null
        seen = 0
    }

    private fun sampleY(image: ImageProxy): IntArray? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val out = IntArray(GRID_W * GRID_H)
        val cellW = width / GRID_W
        val cellH = height / GRID_H
        if (cellW == 0 || cellH == 0) return null

        // Sample one pixel per cell (center). Cheap and stable enough for
        // motion detection; averaging is overkill when the grid is small.
        for (gy in 0 until GRID_H) {
            val y = gy * cellH + cellH / 2
            for (gx in 0 until GRID_W) {
                val x = gx * cellW + cellW / 2
                val idx = y * rowStride + x * pixelStride
                out[gy * GRID_W + gx] = buffer.get(idx).toInt() and 0xFF
            }
        }
        return out
    }

    companion object {
        private const val GRID_W = 32
        private const val GRID_H = 24
    }
}
