package com.example.careguardcamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.inference.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelManifestInstrumentedTest {
    @Test
    fun bundledModelManifestMatchesAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = ModelManifest.load(context)

        manifest.verifyAssets(context)

        assertEquals(listOf("normal", "fall", "fallen"), manifest.classOrder)
        assertEquals("images", manifest.yolo.tensor.inputName)
        assertEquals("output0", manifest.yolo.tensor.outputName)
        assertEquals("input", manifest.gru.tensor.inputName)
        assertEquals("output", manifest.gru.tensor.outputName)
    }
}
