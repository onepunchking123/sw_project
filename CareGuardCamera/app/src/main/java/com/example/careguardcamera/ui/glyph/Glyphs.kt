package com.example.careguardcamera.ui.glyph

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 자세 글리프 — 이 앱의 식별 장치. 상태를 색 점이 아니라 사람 모양으로 말한다.
 * stand=정상, lie=낙상·미동, sit=휴식(예비), cam=기기 이벤트, mark=로고, moon=지난밤, lock=프라이버시.
 * 경로는 design/caregiver-mockups.html 의 <symbol id="g-*"> 와 같다.
 */
object Glyphs {
    private fun vec(name: String, build: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(build).build()

    private fun ImageVector.Builder.stroke(width: Float, d: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null, pathBuilder = d)

    private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float) =
        path(fill = SolidColor(Color.Black)) {
            moveTo(cx - r, cy); arcTo(r, r, 0f, true, false, cx + r, cy); arcTo(r, r, 0f, true, false, cx - r, cy); close()
        }

    val Stand: ImageVector by lazy {
        vec("stand") {
            dot(12f, 4f, 2.4f)
            stroke(2.2f) { moveTo(12f, 7.5f); verticalLineTo(14.5f); moveTo(12f, 9f); lineTo(8f, 12f); moveTo(12f, 9f); lineTo(16f, 12f); moveTo(12f, 14.5f); lineTo(9f, 20.5f); moveTo(12f, 14.5f); lineTo(15f, 20.5f) }
        }
    }
    val Lie: ImageVector by lazy {
        vec("lie") {
            dot(4f, 15f, 2.4f)
            stroke(2.2f) { moveTo(7.5f, 15f); horizontalLineTo(14.5f); moveTo(9f, 15f); lineTo(12f, 11f); moveTo(9f, 15f); lineTo(12f, 19f); moveTo(14.5f, 15f); lineTo(20.5f, 12.5f); moveTo(14.5f, 15f); lineTo(20.5f, 17.5f) }
        }
    }
    val Sit: ImageVector by lazy {
        vec("sit") {
            dot(9f, 5f, 2.4f)
            stroke(2.2f) { moveTo(9f, 8.5f); verticalLineTo(14.5f); horizontalLineTo(17f); verticalLineTo(19.5f); moveTo(9f, 14.5f); verticalLineTo(19.5f); moveTo(9f, 10f); lineTo(5.5f, 13f) }
        }
    }
    val Cam: ImageVector by lazy {
        vec("cam") {
            stroke(2f) { moveTo(3f, 7f); horizontalLineTo(14f); verticalLineTo(17f); horizontalLineTo(3f); close(); moveTo(14f, 11f); lineTo(21f, 8f); verticalLineTo(16f); lineTo(14f, 13f) }
        }
    }
    val Mark: ImageVector by lazy {
        vec("mark") {
            stroke(1.8f) { moveTo(12f, 2f); lineTo(20f, 5f); verticalLineTo(11f); curveTo(20f, 16f, 16.5f, 19.5f, 12f, 21f); curveTo(7.5f, 19.5f, 4f, 16f, 4f, 11f); verticalLineTo(5f); close() }
            dot(12f, 8f, 1.8f)
            stroke(1.8f) { moveTo(12f, 10.5f); verticalLineTo(14.5f); moveTo(12f, 12f); lineTo(9.4f, 13.8f); moveTo(12f, 12f); lineTo(14.6f, 13.8f); moveTo(12f, 14.5f); lineTo(10f, 18f); moveTo(12f, 14.5f); lineTo(14f, 18f) }
        }
    }
    val Moon: ImageVector by lazy {
        vec("moon") {
            stroke(2f) { moveTo(20f, 14.5f); arcTo(8f, 8f, 0f, false, true, 9.5f, 4f); arcTo(8f, 8f, 0f, true, false, 20f, 14.5f); close() }
        }
    }
    val Lock: ImageVector by lazy {
        vec("lock") {
            stroke(2f) { moveTo(7.5f, 11f); horizontalLineTo(16.5f); arcTo(2.5f, 2.5f, 0f, false, true, 19f, 13.5f); verticalLineTo(18.5f); arcTo(2.5f, 2.5f, 0f, false, true, 16.5f, 21f); horizontalLineTo(7.5f); arcTo(2.5f, 2.5f, 0f, false, true, 5f, 18.5f); verticalLineTo(13.5f); arcTo(2.5f, 2.5f, 0f, false, true, 7.5f, 11f); close(); moveTo(8f, 11f); verticalLineTo(8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); verticalLineTo(11f) }
        }
    }
}

@Composable
fun Glyph(vector: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(imageVector = vector, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}
