package com.example.careguardcamera

import com.example.careguardcamera.data.*
import org.junit.Assert.*
import org.junit.Test

class SetupRepositoryTest {
    private class MemoryStorage : SetupStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    @Test fun restoresProfileContactsCamerasAndCompletedStateAfterRestart() {
        val storage = MemoryStorage()
        val repo = LocalSetupRepository(storage)
        assertNull(repo.savePatient(" 환자 ", "85", "여성", "서울\n101호"))
        assertNull(repo.saveContact(null, "가족", "자녀", "010-1234-5678"))
        assertNull(repo.connectCamera("12345678", "거실"))
        repo.setPrivacyMode(repo.cameras.value.single().id, PrivacyMode.SKELETON_ONLY)
        repo.selectRole("caregiver")
        assertNull(repo.finishOnboarding())
        val restored = LocalSetupRepository(storage)
        assertTrue(restored.completed)
        assertEquals("환자", restored.patient.value.name)
        assertEquals("서울\n101호", restored.patient.value.address)
        assertEquals(repo.contacts.value, restored.contacts.value)
        assertEquals(repo.cameras.value.map { it.id to it.name }, restored.cameras.value.map { it.id to it.name })
        assertEquals(repo.cameras.value.single().privacyMode, restored.cameras.value.single().privacyMode)
        assertEquals("caregiver", restored.role)
    }

    @Test fun invalidInputCannotCompleteSetupOrCreateContacts() {
        val repo = LocalSetupRepository(MemoryStorage())
        assertNotNull(repo.finishOnboarding())
        assertNotNull(repo.savePatient("환자", "131", "남성", ""))
        assertFalse(repo.patientSaved)
        assertNull(repo.savePatient("환자", "0", "", ""))
        assertNotNull(repo.finishOnboarding())
        assertNotNull(repo.saveContact(null, "가족", "자녀", "abc"))
        assertTrue(repo.contacts.value.isEmpty())
        assertNull(repo.saveContact(null, "가족", "기타", "+821012345678"))
        assertNull(repo.finishOnboarding()) // 카메라 나중에 연결 허용
    }

    @Test fun cameraErrorsRetryRenameAndDisconnectAreConsistent() {
        val storage = MemoryStorage()
        val repo = LocalSetupRepository(storage)
        assertNotNull(repo.connectCamera("123", "거실"))
        assertNotNull(repo.connectCamera("00000000", "거실"))
        assertNotNull(repo.connectCamera("99999999", "거실"))
        assertTrue(repo.cameras.value.isEmpty())
        assertNull(repo.connectCamera("12345678", "거실"))
        assertNotNull(repo.connectCamera("12345678", "중복"))
        assertNull(repo.connectCamera("23456789", "안방"))
        val id = repo.cameras.value.first().id
        assertNull(repo.renameCamera(id, "주방"))
        assertEquals("주방", repo.cameras.value.first().name)
        repo.removeCamera(id)
        assertEquals(listOf("안방"), LocalSetupRepository(storage).cameras.value.map { it.name })
        assertNull(repo.connectCamera("12345678", "재등록"))
    }

    @Test fun contactsUseStableIdsAndUnpairClearsPlacement() {
        val storage = MemoryStorage()
        val repo = LocalSetupRepository(storage)
        repo.saveContact(null, "첫째", "자녀", "01011112222")
        repo.saveContact(null, "둘째", "자녀", "01033334444")
        val first = repo.contacts.value.first().id
        val second = repo.contacts.value.last().id
        repo.removeContact(first)
        assertNull(repo.saveContact(second, "수정", "기타", "01055556666"))
        assertEquals(second, repo.contacts.value.single().id)
        assertNotNull(repo.saveContact(first, "삭제된 항목", "기타", "01055556666"))
        repo.setCameraPaired(true); repo.setCameraPlaced()
        assertTrue(LocalSetupRepository(storage).cameraPlaced)
        repo.setCameraPaired(false)
        val restored = LocalSetupRepository(storage)
        assertFalse(restored.cameraPaired.value)
        assertFalse(restored.cameraPlaced)
    }
}
