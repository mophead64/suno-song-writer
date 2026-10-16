package app.sunosongwriter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.sunosongwriter.ui.AppNavHost
import app.sunosongwriter.ui.theme.SongWriterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            receiveShare(intent)
            receiveOpenRoom(intent)
        }
        setContent {
            SongWriterTheme {
                // Screens crossfade over the theme's background rather than the bare window.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
        receiveOpenRoom(intent)
    }

    /** A Writers' Room notification was tapped. */
    private fun receiveOpenRoom(intent: Intent?) {
        val sessionId = intent?.getLongExtra(EXTRA_OPEN_ROOM, 0L)?.takeIf { it != 0L } ?: return
        (application as App).container.pendingOpenRoom.value = sessionId
    }

    companion object {
        const val EXTRA_OPEN_ROOM = "open_room"
    }

    /** Text shared from another app goes to the share screen (spec 5.16). */
    private fun receiveShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        (application as App).container.pendingShare.value = text
    }
}
