package app.morphe.manager.network.service

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonResponseBodyTest {
    @Test
    fun leadingUtf8BomIsRemovedBeforeJsonParsing() {
        assertEquals(
            "{\"version\":\"0.6.0-nivqo.1\"}",
            normalizeJsonResponseBody("\uFEFF{\"version\":\"0.6.0-nivqo.1\"}")
        )
    }

    @Test
    fun ordinaryJsonIsUnchanged() {
        val body = "{\"version\":\"0.6.0-nivqo.1\"}"
        assertEquals(body, normalizeJsonResponseBody(body))
    }
}
