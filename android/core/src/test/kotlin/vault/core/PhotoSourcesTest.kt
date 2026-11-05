package vault.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoSourcesTest {
    private val realmeGallery = InstalledGallery("com.coloros.gallery3d", isSystemApp = true)
    private val googlePhotos = InstalledGallery(GOOGLE_PHOTOS_PACKAGE, isSystemApp = true)
    private val downloadedGallery = InstalledGallery("org.example.gallery", isSystemApp = false)
    private val files = PhotoSource(PhotoSourceKind.FILES, null)

    @Test
    fun offersGalleryThenGooglePhotosThenFiles() {
        val sources = photoSources(listOf(googlePhotos, realmeGallery), setOf(GOOGLE_PHOTOS_PACKAGE, "com.coloros.gallery3d"))
        assertEquals(
            listOf(
                PhotoSource(PhotoSourceKind.GALLERY, "com.coloros.gallery3d"),
                PhotoSource(PhotoSourceKind.GOOGLE_PHOTOS, GOOGLE_PHOTOS_PACKAGE),
                files,
            ),
            sources,
        )
    }

    @Test
    fun prefersTheBuiltInGalleryOverADownloadedOne() {
        val sources = photoSources(listOf(downloadedGallery, realmeGallery), setOf("org.example.gallery", "com.coloros.gallery3d"))
        assertEquals(PhotoSource(PhotoSourceKind.GALLERY, "com.coloros.gallery3d"), sources.first())
    }

    @Test
    fun leavesOutAGalleryThatCannotPickPhotos() {
        val sources = photoSources(listOf(realmeGallery, googlePhotos), setOf(GOOGLE_PHOTOS_PACKAGE))
        assertEquals(listOf(PhotoSource(PhotoSourceKind.GOOGLE_PHOTOS, GOOGLE_PHOTOS_PACKAGE), files), sources)
    }

    @Test
    fun alwaysOffersFiles() {
        assertEquals(listOf(files), photoSources(emptyList(), emptySet()))
    }
}
