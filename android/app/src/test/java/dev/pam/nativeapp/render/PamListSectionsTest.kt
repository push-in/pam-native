package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PamListSectionsTest {
    // 1 header, 2 rail, 10..12 media, 20..21 files, 3 footer.
    private val ids = listOf(1L, 2L, 10L, 11L, 12L, 20L, 21L, 3L)
    private val sections = mapOf(10L to "media", 11L to "media", 12L to "media", 20L to "files", 21L to "files")
    private val sectionOf: (Long) -> String? = { sections[it] }

    @Test
    fun showsSharedRowsAndOnlyTheActiveSection() {
        assertEquals(listOf(1L, 2L, 10L, 11L, 12L, 3L), visibleSectionItems(ids, "media", sectionOf))
        assertEquals(listOf(1L, 2L, 20L, 21L, 3L), visibleSectionItems(ids, "files", sectionOf))
        assertEquals(listOf(1L, 2L, 3L), visibleSectionItems(ids, "", sectionOf))
        assertEquals(ids, visibleSectionItems(ids, "", { null }))
    }

    @Test
    fun railIsTheLastSharedRowAboveTheSections() {
        assertEquals(2L, sectionRailId(ids, sectionOf))
        assertNull(sectionRailId(listOf(10L, 1L), sectionOf))
        assertNull(sectionRailId(listOf(1L, 2L), sectionOf))
    }

    @Test
    fun switchedInSectionRestoresItsAnchorOrStartsBelowTheRail() {
        val files = visibleSectionItems(ids, "files", sectionOf)
        assertEquals(
            VirtualScrollPosition(3, -40),
            sectionScrollTarget(files, VirtualScrollAnchor(21L, -40), 2L),
        )
        // The saved row left the section (a reload): start below the rail.
        assertEquals(VirtualScrollPosition(1, 0), sectionScrollTarget(files, VirtualScrollAnchor(99L, -40), 2L))
        assertEquals(VirtualScrollPosition(1, 0), sectionScrollTarget(files, null, 2L))
        assertNull(sectionScrollTarget(files, null, null))
    }
}
