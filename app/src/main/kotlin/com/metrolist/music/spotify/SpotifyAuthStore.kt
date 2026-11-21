/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Where the Spotify sign-in cookie (`sp_dc`, the whole account session) is kept: its own SharedPreferences file, never the
 * DataStore. The backup export zips settings.preferences_pb, so a cookie in it would leave the phone inside a backup file;
 * this file is also excluded from Android's Auto Backup and device transfer (res/xml/backup_rules.xml and
 * data_extraction_rules.xml). The value is encrypted with a key in the Android Keystore when the device has a working one.
 * Nothing in this file logs.
 *
 * A Keystore that fails for a moment (right after boot, a vendor bug) is not the same as a key that is gone: only a key that
 * can never decrypt the value again ([CookieDecryption.Invalid]) makes the store forget it. A transient failure
 * ([CookieDecryption.Unavailable]) keeps the stored cookie and the user signed in; the next read tries again.
 */

package com.metrolist.music.spotify

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What a stored value turned into. */
sealed interface CookieDecryption {
    class Plain(val value: String) : CookieDecryption

    /** The value can never be decrypted: a different key, damaged text, a key that was invalidated or deleted. */
    object Invalid : CookieDecryption

    /** The Keystore failed this time for a reason that may pass (not available yet, provider error). The value is kept. */
    object Unavailable : CookieDecryption
}

/** Turns the cookie into what is stored and back. Never throws. */
interface SpotifyCookieCipher {
    /** The text to store, or null when this device cannot encrypt right now. */
    fun encrypt(plain: String): String?

    fun decrypt(stored: String): CookieDecryption

    /** Deletes the key itself (sign-out). The default does nothing. */
    fun destroyKey() {}
}

/** AES-GCM with [key] (the Keystore key on a phone, any key in a test). */
class AesGcmCookieCipher(
    private val key: () -> SecretKey,
    private val deleteKey: () -> Unit = {},
) : SpotifyCookieCipher {
    override fun encrypt(plain: String): String? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
        }.getOrNull()

    override fun decrypt(stored: String): CookieDecryption {
        val bytes =
            try {
                Base64.decode(stored, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                return CookieDecryption.Invalid
            }
        if (bytes.size <= IV_SIZE) return CookieDecryption.Invalid
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
            CookieDecryption.Plain(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
        } catch (e: Exception) {
            if (isPermanent(e)) CookieDecryption.Invalid else CookieDecryption.Unavailable
        }
    }

    override fun destroyKey() {
        runCatching { deleteKey() }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "auralys_spotify_session_key"

        /**
         * True for the failures that mean the stored value is lost for good: the tag does not fit (another key, damaged
         * text), the key was invalidated or can no longer be recovered. Everything else (a Keystore that is not ready, a
         * provider error) may pass, so it is not treated as the end of the session.
         */
        internal fun isPermanent(e: Throwable): Boolean =
            e is AEADBadTagException ||
                e is BadPaddingException ||
                e is IllegalBlockSizeException ||
                e is KeyPermanentlyInvalidatedException ||
                e is UnrecoverableKeyException

        /** The key in the Android Keystore, created on first use. */
        fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val spec =
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }

        /** Deletes the key from the Android Keystore (sign-out). Nothing happens if there is none. */
        fun deleteKeystoreKey() {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        }
    }
}

/** What reading the store found. */
sealed interface StoredCookie {
    class Present(val value: String) : StoredCookie

    /** Nothing is stored, or what was stored could never be read again (it has been forgotten). */
    object Absent : StoredCookie

    /** Something is stored but the Keystore could not open it now. It is still there; ask again later. */
    object Unavailable : StoredCookie
}

class SpotifyAuthStore(
    private val prefs: SharedPreferences,
    private val cipher: SpotifyCookieCipher,
    /**
     * When the cipher cannot encrypt at the moment of writing (a Keystore that does not work on this phone), keep the cookie
     * as plain text in this private, backup-excluded file instead of failing the sign-in. Off = the write fails instead.
     */
    private val allowPlainFallback: Boolean = true,
) {
    /**
     * Keeps [cookie]. Returns false when it could not be written. Always tries the cipher first, so a cookie that was kept as
     * plain text is encrypted by the next successful write.
     */
    fun set(cookie: String): Boolean {
        val value = cookie.trim().takeIf { it.isNotEmpty() } ?: return false
        val encrypted = cipher.encrypt(value)
        val stored =
            when {
                encrypted != null -> ENCRYPTED + encrypted
                allowPlainFallback -> PLAIN + value
                else -> return false
            }
        return prefs.edit().putString(KEY, stored).commit()
    }

    /** True when something is stored. Cheap (no decryption), so app start can tell "connected" without opening the Keystore. */
    fun has(): Boolean = !prefs.getString(KEY, null).isNullOrEmpty()

    /** True when the stored value is the plain-text fallback (the Keystore could not encrypt when it was written). */
    fun isPlainFallback(): Boolean = prefs.getString(KEY, null)?.startsWith(PLAIN) == true

    /**
     * Reads the cookie. A value that can never be read again is forgotten ([StoredCookie.Absent]); one the Keystore only
     * failed to open this time is kept ([StoredCookie.Unavailable]). A plain-text value is encrypted again here when the
     * Keystore works now.
     */
    fun read(): StoredCookie {
        val stored = prefs.getString(KEY, null) ?: return StoredCookie.Absent
        val outcome: CookieDecryption =
            when {
                stored.startsWith(ENCRYPTED) -> cipher.decrypt(stored.removePrefix(ENCRYPTED))
                stored.startsWith(PLAIN) -> CookieDecryption.Plain(stored.removePrefix(PLAIN))
                else -> CookieDecryption.Invalid
            }
        return when (outcome) {
            is CookieDecryption.Plain -> {
                if (outcome.value.isBlank()) {
                    clear()
                    StoredCookie.Absent
                } else {
                    if (stored.startsWith(PLAIN)) set(outcome.value)
                    StoredCookie.Present(outcome.value)
                }
            }
            CookieDecryption.Invalid -> {
                clear()
                StoredCookie.Absent
            }
            CookieDecryption.Unavailable -> StoredCookie.Unavailable
        }
    }

    /** The cookie, or null when there is none or it cannot be read right now (see [read] to tell those apart). */
    fun get(): String? = (read() as? StoredCookie.Present)?.value

    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    /** Sign-out: forgets the value and deletes the Keystore key too. */
    fun wipe() {
        clear()
        cipher.destroyKey()
    }

    companion object {
        /** The file's name: also named in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS_FILE = "auralys_spotify_auth"
        private const val KEY = "session"
        private const val ENCRYPTED = "k1:"
        private const val PLAIN = "p1:"
    }
}
