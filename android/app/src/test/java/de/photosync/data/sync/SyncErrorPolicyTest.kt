package de.photosync.data.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class SyncErrorPolicyTest {
    private fun http(status: Int) = HttpException(
        Response.error<Unit>(status, "{}".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun unauthorizedIsTerminalAuthenticationFailure() {
        val error = http(401)
        assertTrue(error.isAuthenticationFailure())
        assertFalse(error.isRetryable())
    }

    @Test
    fun temporaryNetworkAndServerFailuresRemainRetryable() {
        assertTrue(IOException("offline").isRetryable())
        assertTrue(http(503).isRetryable())
        assertFalse(http(422).isRetryable())
        assertFalse(IOException("offline").isAuthenticationFailure())
    }

    @Test
    fun successfulBatchesContinueWithoutFailureRetry() {
        assertTrue(syncWorkDisposition(false, true) == SyncWorkDisposition.CONTINUE)
        assertTrue(syncWorkDisposition(false, false) == SyncWorkDisposition.SUCCESS)
        assertTrue(syncWorkDisposition(true, true) == SyncWorkDisposition.RETRY)
    }
}
