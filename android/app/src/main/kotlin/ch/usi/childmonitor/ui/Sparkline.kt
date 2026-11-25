package ch.usi.childmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.usi.childmonitor.domain.VitalPoint

@Composable
fun Sparkline(points: List<VitalPoint>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.value }
        val low = values.min()
        val high = values.max()
        val span = (high - low).coerceAtLeast(MIN_SPAN).toFloat()
        val padding = 4.dp.toPx()
        val usableHeight = size.height - 2 * padding
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = index * stepX
            val y = padding + usableHeight * (1f - (point.value - low) / span)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val last = points.last()
        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = Offset(size.width, padding + usableHeight * (1f - (last.value - low) / span)),
        )
    }
}

private const val MIN_SPAN = 4
