package de.photosync.domain

import de.photosync.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `adds https and canonical trailing slash`() {
        if (BuildConfig.IS_PRODUCTION) {
            assertEquals(BuildConfig.DEFAULT_SERVER_URL, ServerAddress.normalize(BuildConfig.DEFAULT_SERVER_URL))
        } else {
            assertEquals("https://photosync.example/", ServerAddress.normalize(" photosync.example "))
        }
        if (BuildConfig.IS_PRODUCTION) {
            assertNull(ServerAddress.normalize("http://192.168.1.10:3000"))
        } else {
            assertEquals("http://192.168.1.10:3000/", ServerAddress.normalize("http://192.168.1.10:3000"))
        }
    }

    @Test
    fun `rejects malformed and non-server addresses`() {
        assertNull(ServerAddress.normalize("ftp://photosync.example"))
        assertNull(ServerAddress.normalize("https://photosync.example/api?token=secret"))
        assertNull(ServerAddress.normalize("not a host"))
    }
}
