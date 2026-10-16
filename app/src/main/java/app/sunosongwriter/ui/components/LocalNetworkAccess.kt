package app.sunosongwriter.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.sunosongwriter.data.llm.CleartextPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Android 17 blocks connections to local-network addresses (LAN, Tailscale, the emulator's 10.0.2.2)
 * for apps targeting SDK 37 unless they hold ACCESS_LOCAL_NETWORK, a runtime permission.
 * Ollama and LM Studio backends need it.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17 = 37

    fun isLocalUrl(url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        val loopback = host == "localhost" || host.startsWith("127.") || host == "::1"
        return !loopback && CleartextPolicy.isLocalHost(host)
    }

    fun needsPermission(context: Context, url: String): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 &&
            isLocalUrl(url) &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED
}

/**
 * Returns a function that runs an action against [url], first asking for the local network
 * permission when it's needed. [onDenied] runs if the user refuses; the action still runs, and
 * will fail with a connection error that the screen explains.
 */
@Composable
fun rememberLocalNetworkGate(onDenied: () -> Unit): (url: String, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onDenied()
        pending?.invoke()
        pending = null
    }
    return { url, action ->
        if (LocalNetworkAccess.needsPermission(context, url)) {
            pending = action
            launcher.launch(LocalNetworkAccess.PERMISSION)
        } else {
            action()
        }
    }
}
