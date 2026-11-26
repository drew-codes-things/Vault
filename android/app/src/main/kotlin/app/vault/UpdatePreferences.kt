package app.vault

import android.content.SharedPreferences
import vault.core.UpdateNoticeStore

const val LATEST_RELEASE_API = "https://api.github.com/repos/drew-codes-things/Vault/releases/latest"
const val RELEASES_PAGE = "https://github.com/drew-codes-things/Vault/releases/latest"
const val UPDATE_PREFERENCES = "updates"
private const val CHECKS_ENABLED = "checks_enabled"
private const val DISMISSED_VERSION = "dismissed_version"

class UpdatePreferences(private val preferences: SharedPreferences) : UpdateNoticeStore {
    override var checksEnabled: Boolean
        get() = preferences.getBoolean(CHECKS_ENABLED, true)
        set(value) = preferences.edit().putBoolean(CHECKS_ENABLED, value).apply()

    override var dismissedVersion: String
        get() = preferences.getString(DISMISSED_VERSION, "").orEmpty()
        set(value) = preferences.edit().putString(DISMISSED_VERSION, value).apply()
}
