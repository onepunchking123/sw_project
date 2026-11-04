package com.example.careguardcamera.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGRadius
import com.example.careguardcamera.ui.theme.CGSize
import com.example.careguardcamera.ui.theme.CGType

/* ── 색 헬퍼 ───────────────────────────────────────────── */

fun Severity.color(): Color = when (this) {
    Severity.SAFE -> CGColor.Safe
    Severity.WARNING -> CGColor.Warning
    Severity.CRITICAL -> CGColor.Critical
    Severity.NEUTRAL -> CGColor.Ink3
}

fun Severity.softColor(): Color = when (this) {
    Severity.SAFE -> CGColor.SafeSoft
    Severity.WARNING -> CGColor.WarningSoft
    Severity.CRITICAL -> CGColor.CriticalSoft
    Severity.NEUTRAL -> CGColor.Surface
}

fun Severity.glyph(): ImageVector = when (this) {
    Severity.SAFE -> Glyphs.Stand
    Severity.WARNING, Severity.CRITICAL -> Glyphs.Lie
    Severity.NEUTRAL -> Glyphs.Cam
}

/* ── 버튼 ──────────────────────────────────────────────── */

enum class ButtonKind { Brand, Secondary, Soft, Red, Ghost }

@Composable
fun CGButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Brand,
    large: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    textStyle: TextStyle? = null
) {
    val (bg, fg) = when (kind) {
        ButtonKind.Brand -> CGColor.Brand to Color.White
        ButtonKind.Secondary -> CGColor.Paper to CGColor.Ink
        ButtonKind.Soft -> CGColor.BrandSoft to CGColor.BrandInk
        ButtonKind.Red -> CGColor.Critical to Color.White
        ButtonKind.Ghost -> Color.Transparent to CGColor.Ink2
    }
    val shadowColor = when (kind) {
        ButtonKind.Brand -> Color(0x2E6B3FD6)
        ButtonKind.Red -> Color(0x33D7263D)
        else -> Color.Transparent
    }
    var m = modifier
        .heightIn(min = if (large) CGSize.Emergency else CGSize.Touch)
        .alpha(if (enabled) 1f else 0.4f)
    if (shadowColor != Color.Transparent) m = m.shadow(10.dp, CircleShape, spotColor = shadowColor, ambientColor = shadowColor)
    m = m.clip(CircleShape).background(bg)
    if (kind == ButtonKind.Secondary) m = m.border(1.5.dp, CGColor.Line, CircleShape)
    Row(
        m.clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        }
        Text(text, style = textStyle ?: if (large) CGType.BodyBold.copy(fontSize = 17.sp) else CGType.BodyBold, color = fg, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
    }
}

/* ── 칩 ────────────────────────────────────────────────── */

@Composable
fun CGChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, critical: Boolean = false, dark: Boolean = false) {
    val bg = when {
        selected -> if (critical) CGColor.Critical else CGColor.Brand
        dark -> Color(0x1AFFFFFF)
        else -> CGColor.Paper
    }
    val fg = when {
        selected -> Color.White
        critical -> CGColor.Critical
        dark -> Color.White
        else -> CGColor.Ink
    }
    val border = when {
        selected || dark -> Color.Transparent
        critical -> CGColor.Critical
        else -> CGColor.Line
    }
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, border, CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = CGType.Label, color = fg, maxLines = 1, softWrap = false) }
}

/* ── 섹션 라벨 ─────────────────────────────────────────── */

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = CGType.Section, color = CGColor.Ink3, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = CGType.Caption, color = CGColor.Brand, maxLines = 1, softWrap = false, modifier = Modifier.clickable(enabled = onAction != null) { onAction?.invoke() })
    }
}

@Composable
fun GroupLabel(text: String) {
    Text(text, style = CGType.Section, color = CGColor.Ink3, modifier = Modifier.padding(start = 2.dp, top = 18.dp, bottom = 6.dp))
}

/* ── 구분선 ────────────────────────────────────────────── */

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(CGColor.Line))
}

/* ── 평평한 이벤트 행: 시각 · 제목 · 설명 · 우측 글리프 ─── */

@Composable
fun FlatRow(
    time: String,
    title: String,
    detail: String?,
    severity: Severity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    glyphLeft: Boolean = false
) {
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = CGType.Label, color = CGColor.Ink3, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 48.dp))
            if (glyphLeft) { Glyph(severity.glyph(), severity.color(), 20.dp); Spacer(Modifier.width(12.dp)) } else Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = CGType.BodyBold, color = if (severity == Severity.CRITICAL) CGColor.Critical else CGColor.Ink)
                if (detail != null) Text(detail, style = CGType.Caption, color = CGColor.Ink2)
            }
            if (!glyphLeft) { Spacer(Modifier.width(10.dp)); Glyph(severity.glyph(), severity.color(), 20.dp) }
        }
        HairLine()
    }
}

/* ── 앱바 ──────────────────────────────────────────────── */

@Composable
fun CGTopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    dark: Boolean = false,
    centerTitle: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val fg = if (dark) Color.White else CGColor.Ink
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconCircle(onClick = onBack, dark = dark) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, "뒤로", tint = fg, modifier = Modifier.size(18.dp).padding(start = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        if (title != null) Text(
            title,
            style = if (centerTitle) CGType.Label else CGType.Title,
            color = if (centerTitle) CGColor.Ink3 else fg,
            textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f)
        ) else Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun IconCircle(onClick: () -> Unit, dark: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (dark) Color(0x1FFFFFFF) else CGColor.Paper)
            .border(if (dark) 0.dp else 1.5.dp, if (dark) Color.Transparent else CGColor.Line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

/* ── LIVE 배지 ─────────────────────────────────────────── */

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(CGColor.BrandSoft).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(Glyphs.Cam, CGColor.Brand, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text("LIVE", style = CGType.LabelBold.copy(fontSize = 12.sp, letterSpacing = 0.5.sp), color = CGColor.Brand)
    }
}

/* ── 상태 알약 ─────────────────────────────────────────── */

@Composable
fun StatusPill(text: String, tone: Severity, outlined: Boolean = false) {
    val fg = if (tone == Severity.NEUTRAL) CGColor.Ink3 else tone.color()
    val bg = if (outlined) Color.Transparent else if (tone == Severity.NEUTRAL) CGColor.Surface else tone.softColor()
    Box(
        Modifier.clip(CircleShape).background(bg).border(if (outlined) 1.dp else 0.dp, if (outlined) CGColor.Line else Color.Transparent, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Text(text, style = CGType.LabelBold.copy(fontSize = 12.5.sp), color = fg, maxLines = 1, softWrap = false) }
}

/* ── 아바타 ────────────────────────────────────────────── */

@Composable
fun Avatar(initial: String, size: Dp = 52.dp, bg: Color = CGColor.AvatarBg, fg: Color = CGColor.AvatarFg) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initial, style = CGType.Heading, color = fg)
    }
}

/* ── 글리프 박스 (홈 상태 카드) ────────────────────────── */

@Composable
fun GlyphBox(vector: ImageVector, tint: Color, bg: Color, size: Dp = CGSize.GlyphBox, glyphSize: Dp = 26.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Glyph(vector, tint, glyphSize) }
}

/* ── 토글 ──────────────────────────────────────────────── */

@Composable
fun CGSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val bg = if (checked) CGColor.Brand else CGColor.Line
    // 터치 영역 48dp 확보 (시각 트랙은 44×26)
    Box(
        Modifier.size(width = 56.dp, height = 48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(44.dp).height(26.dp).clip(CircleShape).background(bg).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
    }
}

/* ── 깜빡이는 점 ───────────────────────────────────────── */

@Composable
fun BlinkDot(color: Color, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}

/* ── 안내 줄 (brandSoft) ──────────────────────────────── */

@Composable
fun TrustNote(text: String, glyph: ImageVector = Glyphs.Lock) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CGColor.BrandSoft).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Glyph(glyph, CGColor.Brand, 18.dp, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = CGType.Caption.copy(lineHeight = 19.sp), color = CGColor.BrandInk)
    }
}

/* ── 설정 행 ───────────────────────────────────────────── */

@Composable
fun SettingRow(icon: ImageVector, title: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, last: Boolean = false) {
    Column {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(CGColor.BrandSoft), contentAlignment = Alignment.Center) {
                Glyph(icon, CGColor.BrandInk, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = CGType.Body, color = CGColor.Ink, maxLines = 2, modifier = Modifier.weight(1f))
            if (trailing != null) trailing() else {
                if (value != null) Text(value, style = CGType.Caption, color = CGColor.Ink3, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.width(6.dp))
                Text("›", style = CGType.Heading, color = CGColor.Ink3)
            }
        }
        if (!last) HairLine(Modifier.padding(start = 60.dp))
    }
}

@Composable
fun RowsCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(CGRadius.Rows), spotColor = Color(0x0D3C286E), ambientColor = Color(0x0D3C286E)).clip(RoundedCornerShape(CGRadius.Rows)).background(CGColor.Paper)) { content() }
}
