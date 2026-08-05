package dev.pam.nativeapp.modules

import android.provider.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MediaLibraryModuleTest {
    @Test
    fun recentAssetsUseCameraRollCompatibleOrdering() {
        assertArrayEquals(
            arrayOf(
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
            ),
            MediaLibraryModule.recentSortColumns(),
        )
    }
}
