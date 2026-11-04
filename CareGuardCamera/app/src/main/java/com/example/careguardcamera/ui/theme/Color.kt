package com.example.careguardcamera.ui.theme

import androidx.compose.ui.graphics.Color

/** CareGuard 디자인 시스템 v0.2 색 토큰 (design/design-system.html). */
object CGColor {
    // 브랜드 보라
    val Brand = Color(0xFF6B3FD6)
    val BrandDeep = Color(0xFF5730B8)
    val BrandSoft = Color(0xFFF0EBFD)
    val BrandInk = Color(0xFF4A2AA3)
    val BrandOnDark = Color(0xFFB39DFF)   // 영상 위 관절점·다크 배경 포인트

    // 중립 (보라 기운 섞인 회색)
    val Ink = Color(0xFF1B1D22)
    val Ink2 = Color(0xFF5A5566)
    val Ink3 = Color(0xFF948FA0)
    val Line = Color(0xFFEEEAF5)
    val Surface = Color(0xFFF8F6FC)
    val Paper = Color(0xFFFFFFFF)

    // 상태색
    val Critical = Color(0xFFD7263D)
    val CriticalSoft = Color(0xFFFFEDEF)
    val CriticalOnDark = Color(0xFFFF5A6A)
    val Warning = Color(0xFFC47A00)
    val WarningSoft = Color(0xFFFFF4E3)
    val Safe = Color(0xFF2A9A6A)
    val SafeSoft = Color(0xFFE6F6EE)
    val SafeOnDark = Color(0xFF4CC98A)

    // 라이브/카메라 다크 화면
    val Dark = Color(0xFF14171D)
    val DarkRoom = Color(0xFF2E333B)
    val DarkFloor = Color(0xFF4A4239)
    val DarkFurniture = Color(0xFF20242A)

    // 아바타 (복숭아)
    val AvatarBg = Color(0xFFF6E9E1)
    val AvatarFg = Color(0xFF8A5A3C)

    // 기록 분류
    val Cat1 = Color(0xFF6B3FD6) // 증상
    val Cat2 = Color(0xFF9D7BF2) // 투약
    val Cat3 = Color(0xFFC9B8FA) // 복약
    val Cat4 = Color(0xFF8B919C) // 환경
    val Cat5 = Color(0xFF4A2AA3) // 진료
}
