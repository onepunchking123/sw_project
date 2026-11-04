package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGSpace
import com.example.careguardcamera.ui.theme.CGType

/** 1. 역할 선택. 보호자용 / 카메라용. */
@Composable
fun RoleSelectScreen(onCaregiver: () -> Unit, onCamera: () -> Unit) {
    var role by remember { mutableStateOf("caregiver") }
    Column(
        Modifier.fillMaxSize().background(CGColor.Paper).systemBarsPadding().padding(horizontal = CGSpace.Gutter),
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(CGColor.Brand), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Mark, androidx.compose.ui.graphics.Color.White, 34.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text("역할을 선택해주세요", style = CGType.Headline, color = CGColor.Ink)
            Spacer(Modifier.height(6.dp))
            Text("사용 목적에 맞는 역할을 고르면 거기에 맞춘 화면으로 시작합니다", style = CGType.Label, color = CGColor.Ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Spacer(Modifier.height(28.dp))
        RoleCard("보호자용", "환자 상태 확인, 알림 수신, 기록 관리", role == "caregiver") { role = "caregiver" }
        Spacer(Modifier.height(10.dp))
        RoleCard("카메라용", "환자 방에 거치해 영상 분석과 감지를 맡는 기기", role == "camera") { role = "camera" }
        Spacer(Modifier.height(28.dp))
        CGButton("계속하기", onClick = { if (role == "caregiver") onCaregiver() else onCamera() }, large = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("계속하면 서비스 이용약관과 개인정보 처리방침에 동의하게 됩니다", style = CGType.Caption, color = CGColor.Ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RoleCard(title: String, desc: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (on) CGColor.BrandSoft else CGColor.Paper)
            .border(if (on) 2.dp else 1.5.dp, if (on) CGColor.Brand else CGColor.Line, shape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Text(title, style = CGType.Heading, color = CGColor.Ink)
        Spacer(Modifier.height(4.dp))
        Text(desc, style = CGType.Caption, color = CGColor.Ink2)
    }
}
