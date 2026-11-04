package com.example.careguardcamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.StatusPill
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType

@Composable
fun PairedScreen(onPlace: () -> Unit) {
    val state by CameraDeviceRepository.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))
            Box(Modifier.size(84.dp).clip(CircleShape).background(CGColor.SafeSoft), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Stand, CGColor.Safe, 44.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text("연동이 완료되었어요", style = CGType.Headline.copy(fontSize = 26.sp, lineHeight = 34.sp), color = CGColor.Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "거치 후 카메라 앱이 켜져 있는 동안 서버에 접속 상태를 보냅니다.",
                style = CGType.Label.copy(lineHeight = 21.sp),
                color = CGColor.Ink2,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                    .border(1.dp, CGColor.Line, RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Text("연결된 카메라", style = CGType.LabelBold, color = CGColor.Ink3)
                Spacer(Modifier.height(10.dp))
                Text(state.name, style = CGType.BodyBold.copy(fontSize = 17.sp), color = CGColor.Ink)
                Spacer(Modifier.height(8.dp))
                StatusPill("페어링 완료", Severity.SAFE)
            }
        }
        Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp).navigationBarsPadding()) {
            CGButton("거치 위치 잡기", onPlace, Modifier.fillMaxWidth(), kind = ButtonKind.Brand, large = true)
        }
    }
}
