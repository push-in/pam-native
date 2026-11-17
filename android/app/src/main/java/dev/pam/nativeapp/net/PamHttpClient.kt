package dev.pam.nativeapp.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The process-wide HTTP stack (HTTP module, images). One connection pool, and
 * Happy Eyeballs (RFC 8305): IPv6 and IPv4 connects interleave 250 ms apart,
 * so a network whose IPv6 is broken costs a quarter second per new
 * connection instead of a 10-15 s connect timeout per address. Redirects are
 * followed by the callers that allow them. Callers derive their timeouts with
 * `newBuilder()`, which shares the pool.
 */
internal object PamHttpClient {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .fastFallback(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
