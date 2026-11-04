package com.example.careguardcamera

import com.example.careguardcamera.data.normalizeServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerOriginTest {
    @Test fun normalizesSchemeHostCaseAndDefaultPorts() {
        assertEquals("http://example.com", normalizeServerOrigin("HTTP://Example.COM:80/"))
        assertEquals("https://example.com", normalizeServerOrigin("https://EXAMPLE.com:443"))
        assertEquals("http://127.0.0.1:8080", normalizeServerOrigin(" http://127.0.0.1:8080 "))
    }

    @Test fun rejectsNonOriginUrls() {
        assertNull(normalizeServerOrigin("http://user@example.com"))
        assertNull(normalizeServerOrigin("http://example.com/api"))
        assertNull(normalizeServerOrigin("http://example.com?debug=true"))
        assertNull(normalizeServerOrigin("http://example.com/#fragment"))
        assertNull(normalizeServerOrigin("ftp://example.com"))
    }
}
