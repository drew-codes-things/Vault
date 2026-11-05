package vault.core

const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"

enum class PhotoSourceKind { GALLERY, GOOGLE_PHOTOS, FILES }

data class PhotoSource(val kind: PhotoSourceKind, val packageName: String?)

data class InstalledGallery(val packageName: String, val isSystemApp: Boolean)

fun photoSources(galleries: List<InstalledGallery>, photoPickerPackages: Set<String>): List<PhotoSource> = buildList {
    phoneGallery(galleries, photoPickerPackages)?.let { add(PhotoSource(PhotoSourceKind.GALLERY, it)) }
    if (GOOGLE_PHOTOS_PACKAGE in photoPickerPackages) add(PhotoSource(PhotoSourceKind.GOOGLE_PHOTOS, GOOGLE_PHOTOS_PACKAGE))
    add(PhotoSource(PhotoSourceKind.FILES, null))
}

private fun phoneGallery(galleries: List<InstalledGallery>, photoPickerPackages: Set<String>): String? =
    galleries
        .filter { it.packageName != GOOGLE_PHOTOS_PACKAGE && it.packageName in photoPickerPackages }
        .sortedByDescending { it.isSystemApp }
        .firstOrNull()
        ?.packageName
