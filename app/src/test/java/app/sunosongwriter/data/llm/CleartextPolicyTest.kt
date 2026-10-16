package app.sunosongwriter.data.llm

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextPolicyTest {
    private fun allowed(url: String) = CleartextPolicy.isAllowed(url.toHttpUrl())

    @Test
    fun httpsIsAlwaysAllowed() {
        assertTrue(allowed("https://openrouter.ai/api/v1"))
    }

    @Test
    fun httpIsAllowedForLocalAddresses() {
        listOf(
            "http://10.0.2.2:11434/v1",
            "http://localhost:1234/v1",
            "http://127.0.0.1:11434",
            "http://192.168.1.20:11434",
            "http://172.20.0.5:11434",
            "http://100.101.102.103:11434", // Tailscale
            "http://macbook.local:11434",
            "http://macbook:11434",
            "http://[::1]:11434",
        ).forEach { assertTrue(it, allowed(it)) }
    }

    @Test
    fun httpIsRefusedForPublicHosts() {
        listOf(
            "http://api.groq.com/openai/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
            "http://100.128.0.1/v1",
        ).forEach { assertFalse(it, allowed(it)) }
    }
}
