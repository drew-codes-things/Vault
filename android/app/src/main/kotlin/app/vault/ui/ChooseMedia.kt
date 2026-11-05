package app.vault.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import vault.core.InstalledGallery
import vault.core.PhotoSource
import vault.core.photoSources

private const val IMAGE_MIME_TYPE = "image/*"
private const val VIDEO_MIME_TYPE = "video/*"
val PHOTO_AND_VIDEO_TYPES = arrayOf(IMAGE_MIME_TYPE, VIDEO_MIME_TYPE)

private fun pickPhotosIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType(IMAGE_MIME_TYPE)
    .putExtra(Intent.EXTRA_MIME_TYPES, PHOTO_AND_VIDEO_TYPES)
    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

fun installedPhotoSources(packageManager: PackageManager): List<PhotoSource> {
    val galleries = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
        0,
    ).map { resolved ->
        val app = resolved.activityInfo.applicationInfo
        InstalledGallery(app.packageName, isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
    }
    val pickers = packageManager.queryIntentActivities(pickPhotosIntent(), 0)
        .map { it.activityInfo.packageName }
        .toSet()
    return photoSources(galleries, pickers)
}

class ChooseMedia : ActivityResultContract<String, List<Uri>>() {
    override fun createIntent(context: Context, input: String): Intent = pickPhotosIntent().setPackage(input)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData ?: return listOfNotNull(intent.data)
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
