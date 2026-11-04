package com.example.careguardcamera

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.careguardcamera.ui.nav.CaregiverApp
import com.example.careguardcamera.ui.nav.Routes
import com.example.careguardcamera.ui.theme.CGTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everySettingsDestinationOpensAndReturns() {
        compose.setContent { CGTheme { CaregiverApp(startRoute = Routes.SETTINGS, onLogout = {}) } }
        listOf("수정", "보호자 관리", "카메라 기기", "영상 공개 범위", "비상 연락처", "미동(R2) 알림", "데이터 활용 안내", "앱 정보").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithContentDescription("뒤로").assertIsDisplayed().performClick()
            compose.onNodeWithText("환자 · 설정").assertExists()
        }
    }

    @Test
    fun logoutCanBeCancelledBeforeConfirming() {
        var loggedOut = false
        compose.setContent { CGTheme { CaregiverApp(startRoute = Routes.SETTINGS, onLogout = { loggedOut = true }) } }
        compose.onNodeWithText("로그아웃").performScrollTo().performClick()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("환자 · 설정").assertExists()
        compose.onNodeWithText("로그아웃").performScrollTo().performClick()
        compose.onAllNodesWithText("로그아웃").onLast().performClick()
        compose.runOnIdle { assertTrue(loggedOut) }
    }
}
