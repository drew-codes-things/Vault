package app.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "vault-device-key"
private const val BIOMETRIC_ALIAS = "vault-biometric-key"
private const val BIOMETRIC_BLOB = "biometric.bin"
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128

class DeviceSecrets(private val dir: File) {
    init {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(name: String, value: ByteArray, raw: Boolean = false) {
        val sealed = if (raw) {
            value
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.iv + cipher.doFinal(value)
        }
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(sealed)
            stream.fd.sync()
        }
        if (!temp.renameTo(target)) throw IOException("could not store $name")
    }

    fun get(name: String): ByteArray? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val sealed = file.readBytes()
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (error: GeneralSecurityException) {
            Log.w("Vault", "stored secret $name cannot be opened", error)
            null
        }
    }

    fun exists(name: String): Boolean = File(dir, name).exists()

    fun remove(name: String) {
        File(dir, name).delete()
    }

    fun hasBiometricKey(): Boolean = File(dir, BIOMETRIC_BLOB).exists()

    private fun biometricKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(BIOMETRIC_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(BIOMETRIC_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    fun biometricEncryptCipher(): Cipher {
        removeBiometric()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, biometricKey(create = true)) }
    }

    fun biometricDecryptCipher(): Cipher? {
        val blob = File(dir, BIOMETRIC_BLOB).takeIf { it.exists() }?.readBytes() ?: return null
        val key = biometricKey(create = false) ?: return null.also { removeBiometric() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES)) }
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            removeBiometric()
            null
        }
    }

    fun storeBiometric(authenticatedCipher: Cipher, secret: ByteArray) {
        put(BIOMETRIC_BLOB, authenticatedCipher.iv + authenticatedCipher.doFinal(secret), raw = true)
    }

    fun readBiometric(authenticatedCipher: Cipher): ByteArray {
        val blob = File(dir, BIOMETRIC_BLOB).readBytes()
        return authenticatedCipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
    }

    fun removeBiometric() {
        File(dir, BIOMETRIC_BLOB).delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(BIOMETRIC_ALIAS) }
    }
}
