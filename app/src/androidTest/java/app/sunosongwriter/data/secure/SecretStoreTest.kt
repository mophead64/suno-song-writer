package app.sunosongwriter.data.secure

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "secret-store-test.preferences_pb").also { it.delete() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SecretStore(
        PreferenceDataStoreFactory.create(scope = scope) { file },
        KeystoreCipher(alias = "secret-store-test"),
    )

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun roundTripsThroughTheKeystoreAndNeverStoresPlaintext() = runTest {
        val key = "sk-or-v1-0123456789abcdef0123456789abcdef"

        store.put("backend:1", key)

        assertEquals(key, store.get("backend:1"))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(key))
    }

    @Test
    fun removeDeletesTheKey() = runTest {
        store.put("backend:2", "secret-value-for-test")
        store.remove("backend:2")

        assertNull(store.get("backend:2"))
    }

    @Test
    fun hintOnlyForLongKeys() {
        assertEquals("cdef", SecretStore.hintFor("sk-or-v1-0123456789abcdef"))
        assertNull(SecretStore.hintFor("short"))
    }
}
