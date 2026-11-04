package com.example.careguardcamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType

@Composable
fun PlacementScreen(onBack: () -> Unit, onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CGColor.Dark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        CGTopBar(onBack = onBack, dark = true) {
            Text("거치 위치 잡기", style = CGType.LabelBold.copy(fontSize = 17.sp), color = Color.White)
            Spacer(Modifier.weight(1f))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Glyph(Glyphs.Cam, CGColor.BrandOnDark, 42.dp)
            }
            Spacer(Modifier.height(22.dp))
            Text("거치 안내", style = CGType.Title.copy(fontSize = 24.sp), color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "카메라 화면은 이 버전에서 미리보기로 판단하지 않습니다. 아래 안내를 보고 보호자가 환자 공간을 확인하기 쉬운 위치에 세워 주세요.",
                style = CGType.Body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GuideRow(Glyphs.Stand, "침대와 바닥이 함께 보이는 위치")
                GuideRow(Glyphs.Cam, "전원이 연결되어 앱이 계속 켜질 수 있는 위치")
                GuideRow(Glyphs.Lock, "화면이 가려지지 않고 넘어지지 않는 안정적인 위치")
            }
        }
        CGButton(
            "이 위치로 시작하기",
            onStart,
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 10.dp),
            kind = ButtonKind.Brand,
            large = true
        )
    }
}

@Composable
private fun GuideRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Glyph(icon, Color.White.copy(alpha = 0.86f), 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = CGType.Label, color = Color.White.copy(alpha = 0.86f))
    }
}
