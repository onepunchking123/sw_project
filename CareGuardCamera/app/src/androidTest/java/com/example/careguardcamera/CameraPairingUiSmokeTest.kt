package com.example.careguardcamera

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.ApiHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** UI smoke test for the real camera pairing flow against scripts/test-android-api.py. */
@RunWith(AndroidJUnit4::class)
class CameraPairingUiSmokeTest {
    @Test fun cameraUiPairsStartsLiveAndPausesHeartbeatInBackground() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("disposableBackend") == "true")
        val backendUrl = args.getString("backendUrl") ?: "http://10.0.2.2:8080"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val isolated = isolatedContext("pairing-ui-${UUID.randomUUID()}-")
        val activities = mutableListOf<Activity>()
        automation.grantRuntimePermission(isolated.packageName, android.Manifest.permission.CAMERA)

        BackendRepository.initialize(isolated)
        CameraDeviceRepository.initialize(isolated)
        assertNull(BackendRepository.configureServer(backendUrl))
        assertNull(BackendRepository.startNewPatient())
        assertNull(BackendRepository.savePatient("UI 페어링 환자", "80", "여성", "서울"))
        assertNull(BackendRepository.saveContact(null, "보호자", "자녀", "01012345678"))
        assertNull(CameraDeviceRepository.configureServer(backendUrl))

        try {
            val cameraActivity = startCameraActivity(isolated)
            activities += cameraActivity
            // This Android 17 emulator uses 16 KB pages. Keep the platform compatibility
            // warning visible in diagnostics, then acknowledge this run without hiding it globally.
            waitUntil(timeoutMs = 5_000L) {
                findText("Android App Compatibility") != null || findText("보호자 폰에") != null
            }
            if (findText("Android App Compatibility") != null) {
                saveScreenshot(isolated, "camera-page-size-warning.png")
                assertUi(clickNode(requireNotNull(findExactText("OK"))), "Could not acknowledge page-size compatibility mode", isolated)
            }
            saveScreenshot(isolated, "camera-launch.png")

            assertUi(waitForText("보호자 폰에", timeoutMs = 15_000L), "Missing initial camera pairing screen text", isolated)
            assertUi(waitForText("--------", timeoutMs = 5_000L), "Initial pairing placeholder was not visible", isolated)
            assertUi(CameraDeviceRepository.state.value.pairingCode == null, "Pairing code was issued before the user tapped the button", isolated)
            clickText("연결 코드 발급", isolated)
            val code = waitForPairingCode(isolated)
            assertUi(waitForText(code, timeoutMs = 8_000L), "Issued pairing code was not visible", isolated)
            saveScreenshot(isolated, "camera-pairing-code.png")

            assertNull(BackendRepository.connectCamera(code, "거실 카메라"))
            assertUi(waitUntil(timeoutMs = 12_000L) { CameraDeviceRepository.state.value.paired }, "UI polling did not observe paired state", isolated)
            assertUi(waitForText("연동이 완료되었어요", timeoutMs = 12_000L), "Paired screen was not visible", isolated)
            assertUi(waitForText("연결된 카메라", timeoutMs = 5_000L), "Paired camera details were not visible", isolated)
            saveScreenshot(isolated, "camera-paired.png")

            clickText("거치 위치 잡기", isolated)
            assertUi(waitForText("거치 안내", timeoutMs = 8_000L), "Placement guide screen was not visible", isolated)
            clickText("이 위치로 시작하기", isolated)
            assertUi(waitForText("카메라 앱 실행 중", timeoutMs = 8_000L), "Live camera screen was not visible", isolated)
            assertUi(waitForText("온라인", timeoutMs = 10_000L), "Online status was not visible", isolated)
            assertUi(waitUntil(timeoutMs = 10_000L) { CameraDeviceRepository.state.value.lastSeenAt != null }, "Heartbeat timestamp was not recorded", isolated)
            val firstHeartbeat = CameraDeviceRepository.state.value.lastSeenAt
            assertNotNull(firstHeartbeat)
            val patientId = requireNotNull(BackendRepository.currentPatientId())
            val guardianId = requireNotNull(BackendRepository.currentGuardianUserId())
            val cameraId = requireNotNull(CameraDeviceRepository.state.value.cameraId)
            val liveApi = BackendRepository.liveSessionApi()
            var firstSessionId: String? = null
            assertUi(waitUntil(timeoutMs = 15_000L) {
                firstSessionId = runCatching { runBlocking {
                    liveApi.discoverGuardianSession(patientId, cameraId, guardianId).sessionId
                } }.getOrNull()
                firstSessionId != null
            }, "Camera UI did not create a real live session", isolated)
            saveScreenshot(isolated, "camera-live.png")

            assertUi(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME), "Could not send HOME global action", isolated)
            assertUi(waitUntil(timeoutMs = 8_000L) {
                val active = java.util.concurrent.atomic.AtomicBoolean(true)
                instrumentation.runOnMainSync {
                    active.set((cameraActivity as androidx.lifecycle.LifecycleOwner).lifecycle.currentState
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                }
                !active.get()
            }, "Camera activity did not enter background", isolated)
            val pausedHeartbeat = CameraDeviceRepository.state.value.lastSeenAt
            SystemClock.sleep(16_000L)
            assertEquals(pausedHeartbeat, CameraDeviceRepository.state.value.lastSeenAt)
            val stopped = runCatching {
                liveApi.discoverGuardianSession(patientId, cameraId, guardianId)
            }.exceptionOrNull()
            assertUi(stopped is ApiHttpException && stopped.code == 410, "Background live session remained active", isolated)

            resumeCameraTask(cameraActivity, isolated)
            assertUi(waitForText("카메라 앱 실행 중", timeoutMs = 8_000L), "Live camera screen was not visible after resume", isolated)
            assertUi(waitUntil(timeoutMs = 20_000L) {
                val latestHeartbeat = CameraDeviceRepository.state.value.lastSeenAt
                latestHeartbeat != null && latestHeartbeat != pausedHeartbeat
            }, "Heartbeat timestamp did not refresh after resume", isolated)
            assertNotEquals(pausedHeartbeat, CameraDeviceRepository.state.value.lastSeenAt)
            assertUi(waitUntil(timeoutMs = 15_000L) {
                runCatching { runBlocking {
                    liveApi.discoverGuardianSession(patientId, cameraId, guardianId).sessionId != firstSessionId
                } }.getOrDefault(false)
            }, "Foreground resume did not create a new live session", isolated)
        } finally {
            activities.asReversed().forEach { activity ->
                activity.runOnUiThread { activity.finish() }
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun startCameraActivity(context: Context): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(context, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun resumeCameraTask(activity: Activity, context: Context) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appTask = activityManager.appTasks.firstOrNull { it.taskInfo.id == activity.taskId }
        assertUi(appTask != null, "Could not find camera app task ${activity.taskId} to resume", context)
        instrumentation.runOnMainSync { appTask!!.moveToFront() }
        saveScreenshot(context, "camera-resume.png")
    }

    private fun waitForPairingCode(context: Context): String {
        var code: String? = null
        assertUi(waitUntil(timeoutMs = 15_000L) {
            code = CameraDeviceRepository.state.value.pairingCode?.takeIf { it.matches(Regex("[0-9]{8}")) }
            code != null
        }, "Pairing code was not issued", context)
        return code.orEmpty()
    }

    private fun clickText(text: String, context: Context) {
        val node = waitForExactClickableNode(text, timeoutMs = 8_000L)
        assertUi(node != null, "Missing exact clickable accessibility text: $text", context)
        assertUi(clickNode(node!!), "Accessibility node for '$text' was not clickable", context)
    }

    private fun assertUi(condition: Boolean, message: String, context: Context) {
        if (!condition) throw AssertionError("$message\n${diagnostics(context)}")
    }

    private fun waitForText(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs = timeoutMs) { findText(text) != null }

    private fun waitForNode(text: String, timeoutMs: Long): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        waitUntil(timeoutMs = timeoutMs) {
            found = findText(text)
            found != null
        }
        return found
    }

    private fun waitForExactClickableNode(text: String, timeoutMs: Long): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        waitUntil(timeoutMs = timeoutMs) {
            found = findExactText(text)?.takeIf { clickableAncestor(it) != null }
            found != null
        }
        return found
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return findText(root, text)
    }

    private fun findText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString().orEmpty()
        val nodeDescription = node.contentDescription?.toString().orEmpty()
        if (nodeText.contains(text) || nodeDescription.contains(text)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findText(child, text)?.let { return it }
        }
        return null
    }

    private fun findExactText(text: String): AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return findExactText(root, text)
    }

    private fun findExactText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString().orEmpty()
        val nodeDescription = node.contentDescription?.toString().orEmpty()
        if (nodeText == text || nodeDescription == text) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findExactText(child, text)?.let { return it }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return clickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 250L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(intervalMs)
        }
        return condition()
    }

    private fun diagnostics(context: Context): String {
        saveScreenshot(context, "camera-ui-failure-${SystemClock.elapsedRealtime()}.png")
        val state = CameraDeviceRepository.state.value
        return buildString {
            appendLine("CameraDeviceRepository state:")
            appendLine("  serverUrl=${state.serverUrl}")
            appendLine("  cameraId=${state.cameraId}")
            appendLine("  name=${state.name}")
            appendLine("  model=${state.model}")
            appendLine("  paired=${state.paired}")
            appendLine("  disconnected=${state.disconnected}")
            appendLine("  placed=${state.placed}")
            appendLine("  status=${state.status}")
            appendLine("  loading=${state.loading}")
            appendLine("  error=${state.error}")
            appendLine("  secondsRemaining=${state.secondsRemaining}")
            appendLine("  lastSeenAt=${state.lastSeenAt}")
            appendLine("Accessibility root:")
            append(dumpRoot())
        }
    }

    private fun dumpRoot(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return "  <no active root>\n"
        val builder = StringBuilder()
        dumpNode(root, builder, depth = 0, remaining = intArrayOf(160))
        return builder.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int, remaining: IntArray) {
        if (remaining[0] <= 0) return
        remaining[0] -= 1
        val indent = "  ".repeat(depth)
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        builder.append(indent)
            .append(node.className ?: "<class>")
            .append(" text=\"").append(text).append("\"")
            .append(" desc=\"").append(description).append("\"")
            .append(" clickable=").append(node.isClickable)
            .append(" enabled=").append(node.isEnabled)
            .append('\n')
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            dumpNode(child, builder, depth + 1, remaining)
        }
    }

    private fun saveScreenshot(context: Context, name: String) {
        val directory = context.getExternalFilesDir("ui-smoke") ?: return
        directory.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(directory, name)).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }

    private fun isolatedContext(prefix: String): Context {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)

            override fun getNoBackupFilesDir(): File =
                File(super.getNoBackupFilesDir(), prefix).also { it.mkdirs() }

            override fun getExternalFilesDir(type: String?): File? {
                val root = super.getExternalFilesDir(type) ?: return null
                return File(root, prefix).also { it.mkdirs() }
            }
        }
    }
}
