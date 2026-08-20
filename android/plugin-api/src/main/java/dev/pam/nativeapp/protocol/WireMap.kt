package dev.pam.nativeapp.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MAX_WIRE_BYTES = 1024 * 1024

private fun strictWireUtf8(bytes: ByteArray, label: String): String =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("$label is not valid UTF-8", error)
    }

object WireMap {
    fun decode(bytes: ByteArray): Map<String, WireValue> =
        decode(ByteBuffer.wrap(bytes))

    fun decode(source: ByteBuffer): Map<String, WireValue> {
        require(source.remaining() <= MAX_WIRE_BYTES) { "Native module payload exceeds one MiB" }
        val buffer = source.slice().order(ByteOrder.LITTLE_ENDIAN)
        val count = readU16(buffer)
        val result = LinkedHashMap<String, WireValue>(count)
        repeat(count) {
            val keyLength = readU16(buffer)
            require(keyLength in 1..255) { "Invalid native module key length" }
            val key = strictWireUtf8(readBytes(buffer, keyLength), "Native module key")
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,254}"))) {
                "Invalid native module key"
            }
            val value = when (readU8(buffer)) {
                1 -> {
                    val length = readU32(buffer)
                    require(length <= MAX_WIRE_BYTES) { "Native module value is too large" }
                    WireValue.Text(
                        strictWireUtf8(readBytes(buffer, length), "Native module value"),
                    )
                }
                2 -> WireValue.Integer(take(buffer, Long.SIZE_BYTES).long)
                3 -> WireValue.Decimal(
                    take(buffer, Double.SIZE_BYTES).double.also { value ->
                        require(value.isFinite()) { "Native module decimal must be finite" }
                    },
                )
                4 -> when (readU8(buffer)) {
                    0 -> WireValue.Flag(false)
                    1 -> WireValue.Flag(true)
                    else -> error("Invalid native module boolean")
                }
                else -> error("Unknown native module value")
            }
            require(result.put(key, value) == null) { "Duplicate native module key" }
        }
        require(!buffer.hasRemaining()) { "Native module payload has trailing bytes" }
        return result
    }

    fun encode(values: Map<String, WireValue>): ByteArray {
        require(values.size <= 65_535) { "Too many native module values" }
        val output = ByteArrayOutputStream()
        output.write(u16(values.size))
        values.toSortedMap().forEach { (key, value) ->
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            require(
                keyBytes.size in 1..255 && key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,254}")),
            ) { "Invalid native module key" }
            output.write(u16(keyBytes.size))
            output.write(keyBytes)
            when (value) {
                is WireValue.Text -> {
                    val bytes = value.value.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_WIRE_BYTES) { "Native module value is too large" }
                    output.write(1)
                    output.write(u32(bytes.size))
                    output.write(bytes)
                }
                is WireValue.Integer -> {
                    output.write(2)
                    output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.value).array())
                }
                is WireValue.Decimal -> {
                    require(value.value.isFinite()) { "Native module decimal must be finite" }
                    output.write(3)
                    output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value.value).array())
                }
                is WireValue.Flag -> {
                    output.write(4)
                    output.write(if (value.value) 1 else 0)
                }
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_WIRE_BYTES) { "Native module payload exceeds one MiB" }
        }
    }

    private fun readU8(buffer: ByteBuffer): Int =
        take(buffer, 1).get().toInt() and 0xff

    private fun readU16(buffer: ByteBuffer): Int =
        take(buffer, 2).short.toInt() and 0xffff

    private fun readU32(buffer: ByteBuffer): Int {
        val value = take(buffer, 4).int.toLong() and 0xffff_ffffL
        require(value <= Int.MAX_VALUE) { "Native module value is too large" }
        return value.toInt()
    }

    private fun readBytes(buffer: ByteBuffer, length: Int): ByteArray =
        ByteArray(length).also { take(buffer, length).get(it) }

    private fun take(buffer: ByteBuffer, length: Int): ByteBuffer {
        require(length >= 0 && buffer.remaining() >= length) { "Native module payload is truncated" }
        return buffer.slice().order(ByteOrder.LITTLE_ENDIAN).also {
            it.limit(length)
            buffer.position(buffer.position() + length)
        }
    }

    private fun u16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
