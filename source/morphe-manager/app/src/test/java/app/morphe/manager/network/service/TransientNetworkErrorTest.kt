/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.*

/**
 * Retrying the wrong error is worse than not retrying at all: a 404 stays a 404 however many times
 * it is asked, and a rate limit answers to its own layer. These cases pin which failures earn
 * another attempt, including the ones that only reach us wrapped several layers deep.
 */
class TransientNetworkErrorTest {

    @Test
    fun `dropped connections are retried`() {
        // The failure this whole retry path exists for, verbatim from okhttp
        assertTrue(isTransientNetworkError(IOException("unexpected end of stream on https://github.com/...")))
        assertTrue(isTransientNetworkError(EOFException("\\n not found: limit=0")))
        assertTrue(isTransientNetworkError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `server side hiccups are retried`() {
        listOf(408, 500, 502, 503, 504).forEach { code ->
            assertTrue(
                isTransientNetworkError(HttpService.HttpException(HttpStatusCode.fromValue(code))),
                "HTTP $code should be retried"
            )
        }
    }

    @Test
    fun `settled answers are not retried`() {
        listOf(400, 401, 403, 404, 410, 422).forEach { code ->
            assertFalse(
                isTransientNetworkError(HttpService.HttpException(HttpStatusCode.fromValue(code))),
                "HTTP $code should not be retried"
            )
        }
    }

    @Test
    fun `rate limiting is left to its own retry layer`() {
        assertFalse(isTransientNetworkError(HttpService.TooManyRequestsException(1_000L)))
        assertFalse(isTransientNetworkError(HttpService.HttpException(HttpStatusCode.TooManyRequests)))
    }

    @Test
    fun `cancellation is never retried`() {
        assertFalse(isTransientNetworkError(CancellationException("user left the screen")))
        // Cancellation wins even when an IO error sits underneath it
        assertFalse(isTransientNetworkError(CancellationException("cancelled").initCause(IOException("reset"))))
    }

    @Test
    fun `wrapped causes are unwrapped`() {
        val wrapped = RuntimeException("download failed", IOException("connection reset"))
        assertTrue(isTransientNetworkError(wrapped))

        val deeplyWrapped = RuntimeException("outer", RuntimeException("middle", EOFException("inner")))
        assertTrue(isTransientNetworkError(deeplyWrapped))
    }

    @Test
    fun `unrelated failures are not retried`() {
        assertFalse(isTransientNetworkError(IllegalStateException("no release found")))
        assertFalse(isTransientNetworkError(RuntimeException("PAT is required")))
    }

    @Test
    fun `self referencing cause chain terminates`() {
        // A chain that points at itself must not hang the classifier
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(isTransientNetworkError(looping))
    }
}
