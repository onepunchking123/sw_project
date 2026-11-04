package com.example.careguardcamera.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.careguardcamera.ui.theme.CGColor
import kotlin.math.sin

/**
 * 가짜 라이브 뷰. 어두운 방 배경 위에 17점 골격을 Canvas 로 그린다.
 * fallen=false 면 서 있음(흰 선·보라 관절), true 면 누움(빨간 선·빨간 관절). 호흡처럼 미세하게 움직인다.
 */
@Composable
fun SkeletonView(
    modifier: Modifier = Modifier,
    fallen: Boolean = false,
    radius: Dp = 12.dp,
    criticalBorder: Boolean = fallen,
    showSkeleton: Boolean = true,
    content: @Composable () -> Unit = {}
) {
    val t = rememberInfiniteTransition(label = "breath")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(CGColor.DarkRoom)
            .border(if (criticalBorder) 3.dp else 0.dp, if (criticalBorder) CGColor.Critical else Color.Transparent, shape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoom()
            if (showSkeleton) {
                if (fallen) drawLying(phase) else drawStanding(phase)
            }
        }
        content()
    }
}

private fun DrawScope.drawRoom() {
    val w = size.width; val h = size.height
    drawRect(CGColor.DarkFloor, topLeft = Offset(0f, h * 0.68f), size = Size(w, h * 0.32f))
    drawRoundRect(CGColor.DarkFurniture, topLeft = Offset(w * 0.69f, h * 0.32f), size = Size(w * 0.26f, h * 0.24f), cornerRadius = CornerRadius(8f))
    drawRoundRect(Color(0xFF24282E), topLeft = Offset(w * 0.06f, h * 0.40f), size = Size(w * 0.2f, h * 0.18f), cornerRadius = CornerRadius(8f))
}

/** 서 있는 자세. 좌표는 0..1 정규화 (목업 SVG 350×560 기준). */
private fun DrawScope.drawStanding(phase: Float) {
    val w = size.width; val h = size.height
    val bob = sin(phase * 2 * Math.PI).toFloat() * h * 0.004f
    fun p(x: Float, y: Float) = Offset(w * x / 350f, h * y / 560f + bob)
    val head = p(165f, 138f)
    val neck = p(165f, 150f); val hip = p(165f, 200f)
    val lsh = p(131f, 206f); val rsh = p(199f, 206f)
    val lel = p(113f, 262f); val rel = p(217f, 262f)
    val lwr = p(121f, 310f); val rwr = p(209f, 310f)
    val lhp = p(147f, 290f); val rhp = p(183f, 290f)
    val lkn = p(143f, 370f); val rkn = p(187f, 370f)
    val lan = p(139f, 450f); val ran = p(191f, 450f)
    val stroke = Stroke(width = w * 0.009f, cap = StrokeCap.Round)
    val lines = listOf(neck to hip, hip to lsh, hip to rsh, lsh to lel, lel to lwr, rsh to rel, rel to rwr, hip to lhp, hip to rhp, lhp to lkn, lkn to lan, rhp to rkn, rkn to ran)
    lines.forEach { (a, b) -> drawLine(Color.White, a, b, strokeWidth = stroke.width, cap = StrokeCap.Round) }
    drawCircle(CGColor.BrandOnDark, radius = w * 0.026f, center = head)
    listOf(lsh, rsh, lel, rel, lwr, rwr, lhp, rhp, lkn, rkn, lan, ran).forEach { drawCircle(CGColor.BrandOnDark, radius = w * 0.014f, center = it) }
}

/** 누운 자세 (바닥 선 근처). */
private fun DrawScope.drawLying(phase: Float) {
    val w = size.width; val h = size.height
    val bob = sin(phase * 2 * Math.PI).toFloat() * h * 0.002f
    fun p(x: Float, y: Float) = Offset(w * x / 350f, h * y / 560f + bob)
    val head = p(48f, 400f)
    val neck = p(60f, 400f); val hip = p(110f, 400f)
    val ush = p(124f, 374f); val dsh = p(124f, 418f)
    val uel = p(174f, 367f); val del = p(172f, 422f)
    val uwr = p(216f, 387f); val dwr = p(212f, 412f)
    val ukn = p(194f, 396f); val dkn = p(182f, 404f)
    val uan = p(266f, 399f); val dan = p(254f, 410f)
    val c = CGColor.CriticalOnDark
    val sw = w * 0.009f
    listOf(neck to hip, hip to ush, hip to dsh, ush to uel, uel to uwr, dsh to del, del to dwr, hip to ukn, ukn to uan, hip to dkn, dkn to dan)
        .forEach { (a, b) -> drawLine(c, a, b, strokeWidth = sw, cap = StrokeCap.Round) }
    drawCircle(c, radius = w * 0.026f, center = head)
    listOf(ush, dsh, uel, del, uwr, dwr, ukn, dkn, uan, dan).forEach { drawCircle(c, radius = w * 0.014f, center = it) }
}
