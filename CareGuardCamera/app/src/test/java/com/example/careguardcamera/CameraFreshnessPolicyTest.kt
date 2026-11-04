package com.example.careguardcamera

import com.example.careguardcamera.data.CameraConnection
import com.example.careguardcamera.data.CameraFreshnessPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFreshnessPolicyTest {
    @Test fun keepsFreshOnlineCameraOnlineThroughSixtySeconds() {
        assertEquals(
            CameraConnection.ONLINE,
            CameraFreshnessPolicy.connectionAt(
                connection = CameraConnection.ONLINE,
                serverAgeMillis = 55_000L,
                observedElapsedMillis = 5_000L
            )
        )
    }

    @Test fun expiresOnlineCameraAfterSixtySecondsIncludingElapsedClientTime() {
        assertEquals(
            CameraConnection.OFFLINE,
            CameraFreshnessPolicy.connectionAt(
                connection = CameraConnection.ONLINE,
                serverAgeMillis = 55_000L,
                observedElapsedMillis = 6_000L
            )
        )
    }

    @Test fun preservesNonOnlineStates() {
        assertEquals(CameraConnection.UNPAIRED, CameraFreshnessPolicy.connectionAt(CameraConnection.UNPAIRED, null, 120_000L))
        assertEquals(CameraConnection.UNKNOWN, CameraFreshnessPolicy.connectionAt(CameraConnection.UNKNOWN, null, 120_000L))
    }
}
