package dev.pam.nativeapp.modules

import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FilesImportTest {
    @Test fun streamsDocumentLargerThanLegacyLimitWithBoundedMemory() {
        val length = 65L * 1_024 * 1_024
        val output = CountingOutputStream()

        val copied = copyImportedDocument(GeneratedInputStream(length), output, 2L * 1_024 * 1_024 * 1_024)

        assertEquals(length, copied)
        assertEquals(length, output.count)
    }

    @Test fun rejectsDocumentAtExplicitLimitWithoutWritingExtraBytes() {
        val limit = 65L * 1_024 * 1_024
        val output = CountingOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            copyImportedDocument(GeneratedInputStream(limit + 1), output, limit)
        }

        assertEquals(limit, output.count)
    }

    private class GeneratedInputStream(private val length: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int = if (position++ < length) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= length) return -1
            val size = minOf(count.toLong(), length - position).toInt()
            buffer.fill(0, offset, offset + size)
            position += size
            return size
        }
    }

    private class CountingOutputStream : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) { count++ }

        override fun write(buffer: ByteArray, offset: Int, length: Int) { count += length }
    }
}
