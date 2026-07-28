package dev.pam.nativeapp.modules

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpModuleInstrumentedTest {
    @Test
    fun genericRequestPreservesMethodBearerHeaderAndJsonBody() {
        ServerSocket(0, 1).use { server ->
            val received = mutableMapOf<String, String>()
            val serving = thread(name = "pam-http-test-server") {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    received["request"] = reader.readLine()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine()
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            val name = line.substring(0, separator).lowercase()
                            val value = line.substring(separator + 1).trim()
                            received[name] = value
                            if (name == "content-length") contentLength = value.toInt()
                        }
                    }
                    val body = CharArray(contentLength)
                    var offset = 0
                    while (offset < body.size) {
                        val count = reader.read(body, offset, body.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    received["body"] = String(body, 0, offset)

                    val responseBody = """{"accepted":true}"""
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 202 Accepted\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${responseBody.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n" +
                                responseBody
                            ).toByteArray(),
                    )
                }
            }

            val completed = CountDownLatch(1)
            var status: ModuleResultStatus? = null
            var response = ByteArray(0)
            val module = HttpModule()
            module.invoke(
                "request",
                WireMap.encode(
                    mapOf(
                        "url" to WireValue.Text("http://127.0.0.1:${server.localPort}/resource"),
                        "method" to WireValue.Text("PATCH"),
                        "headers" to WireValue.Text(
                            """{"Authorization":"Bearer access-token","Content-Type":"application/json"}""",
                        ),
                        "body" to WireValue.Text("""{"enabled":true}"""),
                        "timeoutMs" to WireValue.Integer(5_000),
                    ),
                ),
            ) { resultStatus, payload ->
                status = resultStatus
                response = payload
                completed.countDown()
            }

            assertTrue("HTTP request did not complete", completed.await(5, TimeUnit.SECONDS))
            serving.join(5_000)
            module.close()

            assertEquals(ModuleResultStatus.SUCCESS, status)
            assertEquals("PATCH /resource HTTP/1.1", received["request"])
            assertEquals("Bearer access-token", received["authorization"])
            assertEquals("application/json", received["content-type"])
            assertEquals("""{"enabled":true}""", received["body"])
            assertEquals(
                mapOf(
                    "statusCode" to WireValue.Integer(202),
                    "body" to WireValue.Text("""{"accepted":true}"""),
                ),
                WireMap.decode(response),
            )
        }
    }
}
