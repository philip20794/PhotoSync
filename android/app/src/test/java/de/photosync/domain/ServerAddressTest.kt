package de.photosync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `adds https and canonical trailing slash`() {
        assertEquals("https://photosync.example/", ServerAddress.normalize(" photosync.example "))
        assertEquals("http://192.168.1.10:3000/", ServerAddress.normalize("http://192.168.1.10:3000"))
    }

    @Test
    fun `rejects malformed and non-server addresses`() {
        assertNull(ServerAddress.normalize("ftp://photosync.example"))
        assertNull(ServerAddress.normalize("https://photosync.example/api?token=secret"))
        assertNull(ServerAddress.normalize("not a host"))
    }
}
