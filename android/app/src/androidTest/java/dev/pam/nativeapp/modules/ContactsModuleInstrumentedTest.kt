package dev.pam.nativeapp.modules

import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsModuleInstrumentedTest {
    @Test
    fun zeroOffsetStartsAtTheFirstContact() {
        val cursor = contactsCursor()

        assertTrue(cursor.moveToOffset(0))
        assertEquals("1", cursor.getString(0))
    }

    @Test
    fun positiveOffsetStartsAtTheRequestedContactAndEndIsRejected() {
        val cursor = contactsCursor()

        assertTrue(cursor.moveToOffset(1))
        assertEquals("2", cursor.getString(0))
        assertFalse(cursor.moveToOffset(3))
    }

    private fun contactsCursor(): MatrixCursor = MatrixCursor(
        arrayOf("_id", "display_name"),
    ).apply {
        addRow(arrayOf("1", "Ana"))
        addRow(arrayOf("2", "Bruno"))
        addRow(arrayOf("3", "Carla"))
    }
}
