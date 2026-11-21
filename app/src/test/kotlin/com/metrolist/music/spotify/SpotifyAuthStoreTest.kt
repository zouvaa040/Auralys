package com.metrolist.music.spotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SpotifyAuthStoreTest {
    private lateinit var context: Context
    private lateinit var key: SecretKey

    private fun prefs() = context.getSharedPreferences(SpotifyAuthStore.PREFS_FILE, Context.MODE_PRIVATE)

    private fun store(cipher: SpotifyCookieCipher = AesGcmCookieCipher({ key })) = SpotifyAuthStore(prefs(), cipher)

    /** A phone whose Keystore cannot encrypt. */
    private object NoEncryption : SpotifyCookieCipher {
        override fun encrypt(plain: String): String? = null

        override fun decrypt(stored: String): CookieDecryption = CookieDecryption.Invalid
    }

    /** A Keystore that opens nothing right now, but may later. */
    private class Hiccup : SpotifyCookieCipher {
        override fun encrypt(plain: String): String? = null

        override fun decrypt(stored: String): CookieDecryption = CookieDecryption.Unavailable
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        prefs().edit().clear().commit()
    }

    @Test
    fun `the cookie is stored and read back`() {
        val s = store()
        assertNull(s.get())
        assertTrue(s.set("AQB-cookie-value_123"))
        assertEquals("AQB-cookie-value_123", s.get())
        assertTrue(s.has())
    }

    @Test
    fun `what lands on disk is not the cookie`() {
        store().set("PLAINTEXT-COOKIE-VALUE")
        val raw = prefs().all.values.joinToString { it.toString() }
        assertFalse(raw.contains("PLAINTEXT-COOKIE-VALUE"))
        assertTrue(raw.startsWith("k1:"))
        assertFalse(store().isPlainFallback())
    }

    @Test
    fun `clear forgets it`() {
        val s = store()
        s.set("abc")
        s.clear()
        assertNull(s.get())
        assertFalse(s.has())
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun `a blank cookie is refused`() {
        val s = store()
        assertFalse(s.set(""))
        assertFalse(s.set("   "))
        assertNull(s.get())
    }

    @Test
    fun `a new cookie replaces the old one`() {
        val s = store()
        s.set("first")
        s.set("second")
        assertEquals("second", s.get())
    }

    // ------------------------------------------------------------ the plain-text fallback is explicit

    @Test
    fun `a phone whose keystore cannot encrypt keeps the cookie in the private file, and says so`() {
        val s = store(NoEncryption)
        assertTrue(s.set("kept"))
        assertEquals("kept", s.get())
        assertTrue(prefs().getString("session", "")!!.startsWith("p1:"))
        assertTrue(s.isPlainFallback())
    }

    @Test
    fun `with the fallback switched off a cookie that cannot be encrypted is not stored at all`() {
        val s = SpotifyAuthStore(prefs(), NoEncryption, allowPlainFallback = false)
        assertFalse(s.set("secret"))
        assertFalse(s.has())
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun `the next successful write encrypts a cookie that was kept as plain text`() {
        assertTrue(store(NoEncryption).set("kept"))
        assertTrue(store().isPlainFallback())
        assertTrue(store().set("kept"))
        assertFalse(store().isPlainFallback())
        assertTrue(prefs().getString("session", "")!!.startsWith("k1:"))
        assertEquals("kept", store().get())
    }

    @Test
    fun `a read encrypts a plain-text cookie again once the keystore works`() {
        store(NoEncryption).set("kept")
        val read = store().read()
        assertEquals("kept", (read as StoredCookie.Present).value)
        assertTrue(prefs().getString("session", "")!!.startsWith("k1:"))
    }

    // ------------------------------------------------------------ a hiccup is not the end of the session

    @Test
    fun `a keystore that fails for now keeps the cookie and the user signed in`() {
        store().set("secret")
        val before = prefs().getString("session", null)
        val s = store(Hiccup())
        assertEquals(StoredCookie.Unavailable, s.read())
        assertNull(s.get())
        assertTrue("still stored", s.has())
        assertEquals("the stored value is untouched", before, prefs().getString("session", null))
        // and when the keystore works again, the cookie is there
        assertEquals("secret", store().get())
    }

    @Test
    fun `a key that can never open the value makes the store forget it`() {
        store().set("secret")
        // a restored backup or a wiped keystore: another key
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val s = SpotifyAuthStore(prefs(), AesGcmCookieCipher({ other }))
        assertEquals(StoredCookie.Absent, s.read())
        assertTrue("the unusable value is removed", prefs().all.isEmpty())
    }

    @Test
    fun `garbage in the file reads as signed out`() {
        prefs().edit().putString("session", "garbage").commit()
        assertEquals(StoredCookie.Absent, store().read())
        assertTrue(prefs().all.isEmpty())
        prefs().edit().putString("session", "k1:!!!not base64!!!").commit()
        assertEquals(StoredCookie.Absent, store().read())
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun `only a lost key is permanent, a busy or failing keystore is not`() {
        assertTrue(AesGcmCookieCipher.isPermanent(AEADBadTagException()))
        assertTrue(AesGcmCookieCipher.isPermanent(javax.crypto.BadPaddingException()))
        assertTrue(AesGcmCookieCipher.isPermanent(java.security.UnrecoverableKeyException()))
        assertFalse(AesGcmCookieCipher.isPermanent(KeyStoreException("not ready")))
        assertFalse(AesGcmCookieCipher.isPermanent(ProviderException("vendor")))
        assertFalse(AesGcmCookieCipher.isPermanent(IllegalStateException()))
        assertFalse(AesGcmCookieCipher.isPermanent(java.security.InvalidKeyException()))
    }

    @Test
    fun `a cipher whose key provider throws is a hiccup, not a lost key`() {
        store().set("secret")
        val failing = AesGcmCookieCipher({ throw KeyStoreException("not ready yet") })
        assertEquals(StoredCookie.Unavailable, SpotifyAuthStore(prefs(), failing).read())
        assertTrue(store().has())
        assertEquals("secret", store().get())
    }

    // ------------------------------------------------------------ sign-out

    @Test
    fun `wipe forgets the value and deletes the key`() {
        var deleted = 0
        val cipher = AesGcmCookieCipher({ key }, deleteKey = { deleted++ })
        val s = SpotifyAuthStore(prefs(), cipher)
        s.set("secret")
        s.wipe()
        assertFalse(s.has())
        assertEquals(1, deleted)
    }

    @Test
    fun `the cipher round-trips and never returns the same text twice`() {
        val cipher = AesGcmCookieCipher({ key })
        val a = cipher.encrypt("value")
        val b = cipher.encrypt("value")
        assertNotNull(a)
        assertTrue(a != b)
        assertEquals("value", (cipher.decrypt(a!!) as CookieDecryption.Plain).value)
        assertEquals(CookieDecryption.Invalid, cipher.decrypt("not base64 !!"))
        assertEquals(CookieDecryption.Invalid, cipher.decrypt("AAAA"))
    }
}
