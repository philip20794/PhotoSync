package de.photosync.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {
    fun normalize(value: String): String? {
        val candidate = value.trim().let { if ("://" in it) it else "https://$it" }
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("https", "http") || url.host.isBlank() || url.query != null || url.fragment != null) {
            return null
        }
        return url.newBuilder().encodedPath("/").build().toString()
    }
}
