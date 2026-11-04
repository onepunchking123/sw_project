package com.example.careguardcamera

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.DeviceCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real HTTP camera pairing test; run only against scripts/test-android-api.py disposable backend. */
@RunWith(AndroidJUnit4::class)
class CameraPairingIntegrationTest {
    @Test fun cameraAppCodePairsWithGuardianAppAndHeartbeatDrivesStatus() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("disposableBackend") == "true")
        val backendUrl = args.getString("backendUrl") ?: "http://10.0.2.2:8080"
        val prefix = "pairing-test-${UUID.randomUUID()}-"
        val isolated = isolatedContext(prefix)
        val bootstrapToken = DeviceCredentialStore(isolated).getOrCreate(backendUrl).token

        BackendRepository.initialize(isolated)
        CameraDeviceRepository.initialize(isolated)

        assertNull(BackendRepository.configureServer(backendUrl))
        assertNull(BackendRepository.startNewPatient())
        assertNull(BackendRepository.savePatient("페어링 환자", "80", "여성", "서울"))
        assertNull(BackendRepository.saveContact(null, "보호자", "자녀", "01012345678"))

        assertNull(CameraDeviceRepository.configureServer(backendUrl))
        assertNull(CameraDeviceRepository.refreshSelfOrIdle())
        assertNull(CameraDeviceRepository.issuePairingCode())
        val restoredCredential = DeviceCredentialStore(isolated).load(backendUrl)
        assertEquals(bootstrapToken, restoredCredential?.token)
        assertNotNull(restoredCredential?.cameraId)
        val code = CameraDeviceRepository.state.value.pairingCode
        assertNotNull(code)
        assertTrue(code!!.matches(Regex("[0-9]{8}")))
        assertFalse(CameraDeviceRepository.state.value.paired)

        assertNull(BackendRepository.connectCamera(code, "거실 카메라"))
        assertTrue(CameraDeviceRepository.pollSelf())
        assertTrue(CameraDeviceRepository.state.value.paired)
        assertEquals("OFFLINE", CameraDeviceRepository.state.value.status)

        assertNull(CameraDeviceRepository.heartbeat())
        assertEquals("ONLINE", CameraDeviceRepository.state.value.status)
        assertNull(BackendRepository.refreshCameras())
        val pairedCamera = BackendRepository.cameras.value.single()
        assertEquals("거실 카메라", pairedCamera.name)
        assertTrue(pairedCamera.online)

        assertNull(BackendRepository.removeCamera(pairedCamera.id))
        assertTrue(BackendRepository.cameras.value.isEmpty())
        assertFalse(CameraDeviceRepository.pollSelf())
        assertEquals("UNPAIRED", CameraDeviceRepository.state.value.status)
        assertTrue(CameraDeviceRepository.state.value.disconnected)
        assertNull(CameraDeviceRepository.state.value.pairingCode)
        assertNotNull(CameraDeviceRepository.issuePairingCode())
        assertNotNull(CameraDeviceRepository.heartbeat())
    }

    private fun isolatedContext(prefix: String): Context {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)

            override fun getNoBackupFilesDir(): File =
                File(super.getNoBackupFilesDir(), prefix).also { it.mkdirs() }
        }
    }
}
