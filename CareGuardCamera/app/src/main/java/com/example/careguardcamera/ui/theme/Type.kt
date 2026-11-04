package com.example.careguardcamera.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.R

/** LINE Seed Sans KR. Regular 400 · Bold 700 두 굵기만 쓴다. */
val LineSeed = FontFamily(
    Font(R.font.lineseedkr_rg, FontWeight.Normal),
    Font(R.font.lineseedkr_bd, FontWeight.Bold)
)

/** 타입 스케일 (sp). 고령 보호자 기준 본문 16, 최소 13. */
object CGType {
    val Display = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp)
    val Headline = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp)
    val Heading = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp)
    val Subheading = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp)
    val Body = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val BodyBold = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp)
    val Label = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val LabelBold = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val Section = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
    val Count = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 60.sp, lineHeight = 60.sp, letterSpacing = (-1).sp)
    val Stat = TextStyle(fontFamily = LineSeed, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp)
}

val CGTypography = Typography(
    displayLarge = CGType.Display,
    headlineMedium = CGType.Headline,
    titleLarge = CGType.Title,
    titleMedium = CGType.Heading,
    titleSmall = CGType.Subheading,
    bodyLarge = CGType.Body,
    bodyMedium = CGType.Label,
    bodySmall = CGType.Caption,
    labelLarge = CGType.LabelBold,
    labelMedium = CGType.Label,
    labelSmall = CGType.Section
)
