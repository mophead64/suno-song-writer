package app.sunosongwriter.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Hands a relay prompt to a chat app (spec 5.2). */
object RelayLauncher {
    enum class Result { OPENED, SHARED, NOT_INSTALLED }

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun clipboardText(context: Context): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()

    /**
     * Copies the prompt, then opens the app so the user can paste it. Starting an activity by package
     * doesn't need package visibility, so no `<queries>` entry is required for user-entered packages.
     */
    fun copyAndOpen(context: Context, packageName: String, prompt: String): Result {
        copy(context, "Prompt", prompt)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            Result.OPENED
        } catch (_: ActivityNotFoundException) {
            openStore(context, packageName)
            Result.NOT_INSTALLED
        }
    }

    /** Shares the prompt straight into the app's compose box, for apps that accept shared text. */
    fun share(context: Context, packageName: String, prompt: String): Result {
        copy(context, "Prompt", prompt)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(send)
            Result.SHARED
        } catch (_: ActivityNotFoundException) {
            copyAndOpen(context, packageName, prompt)
        }
    }

    private fun openStore(context: Context, packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
