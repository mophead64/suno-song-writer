package app.sunosongwriter.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sunosongwriter.App
import app.sunosongwriter.MainActivity
import app.sunosongwriter.R
import app.sunosongwriter.domain.workflow.RoomStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Runs a Writers' Room in the background (section 8): advances the state machine, runs the steps it
 * asks for, and repeats until the room needs the user (the chair, a relay reply), pauses or ends.
 * Every transition is in the database, so a worker restarted after process death carries on.
 */
class RoomWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as App).container

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION, 0)
        if (sessionId == 0L) return Result.failure()
        val c = container
        if (inputData.getBoolean(KEY_RESUME, false)) c.room.resume(sessionId)
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            // Starting a foreground service from the background isn't always allowed; carry on as a normal worker.
        }
        // This worker is unique per room, so anything mid-flight now was cut off by process death.
        c.sessions.resetInterrupted(sessionId)
        while (true) {
            when (val status = c.room.advance(sessionId)) {
                is RoomStatus.Run -> coroutineScope {
                    c.roomNotifier.progress(sessionId)
                    status.messageIds.forEach { id -> launch { c.runner.execute(id) } }
                }
                else -> {
                    c.roomNotifier.attention(sessionId, status)
                    if (status is RoomStatus.Paused) status.pause.resumeAtMillis?.let { c.roomScheduler.resumeAt(sessionId, it) }
                    return Result.success()
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sessionId = inputData.getLong(KEY_SESSION, 0)
        val notification = container.roomNotifier.progressNotification(sessionId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(RoomNotifier.progressId(sessionId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(RoomNotifier.progressId(sessionId), notification)
        }
    }

    companion object {
        const val KEY_SESSION = "session"
        const val KEY_RESUME = "resume"
    }
}

/** Starts, wakes and schedules Writers' Room workers. One unique chain per room. */
class RoomScheduler(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Runs the room now, or after its current worker finishes. */
    fun kick(sessionId: Long) {
        val request = OneTimeWorkRequestBuilder<RoomWorker>()
            .setInputData(workDataOf(RoomWorker.KEY_SESSION to sessionId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(sessionId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Resumes a room paused by an exhausted daily quota once it resets (spec 5.2). */
    fun resumeAt(sessionId: Long, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0) + RESUME_MARGIN_MILLIS
        val request = OneTimeWorkRequestBuilder<RoomWorker>()
            .setInputData(workDataOf(RoomWorker.KEY_SESSION to sessionId, RoomWorker.KEY_RESUME to true))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork("room-resume-$sessionId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(sessionId: Long) {
        workManager.cancelUniqueWork(uniqueName(sessionId))
        workManager.cancelUniqueWork("room-resume-$sessionId")
    }

    private fun uniqueName(sessionId: Long) = "room-$sessionId"

    private companion object {
        const val TAG = "writers-room"
        const val RESUME_MARGIN_MILLIS = 60_000L
    }
}

/** Notifications for Writers' Room runs: ongoing progress, and "needs you" alerts (spec 5.5 manual chair). */
class RoomNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel(PROGRESS, "Writers' Room progress", NotificationManager.IMPORTANCE_LOW))
        system.createNotificationChannel(
            NotificationChannel(ATTENTION, "Writers' Room needs you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When it's your turn as chair, a relay reply is needed, or a run finishes"
            },
        )
    }

    fun progressNotification(sessionId: Long): android.app.Notification =
        NotificationCompat.Builder(context, PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Writers' Room is working")
            .setContentText("Your production team is on it. Tap to watch.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openRoom(sessionId))
            .build()

    fun progress(sessionId: Long) = post(progressId(sessionId), progressNotification(sessionId))

    fun attention(sessionId: Long, status: RoomStatus) {
        manager.cancel(progressId(sessionId))
        val text = when (status) {
            RoomStatus.WaitingForChair -> "Your turn as chair: decide what happens next."
            RoomStatus.WaitingForRelay -> "Waiting for you to relay a prompt to a chat app."
            is RoomStatus.Paused -> status.pause.resumeAtMillis
                ?.let { "Paused until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}: ${status.pause.reason}" }
                ?: "Paused: ${status.pause.reason}"
            RoomStatus.Over -> "Finished. Both variants are ready."
            is RoomStatus.Run -> return
        }
        post(
            attentionId(sessionId),
            NotificationCompat.Builder(context, ATTENTION)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Writers' Room")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openRoom(sessionId))
                .build(),
        )
    }

    fun clear(sessionId: Long) {
        manager.cancel(progressId(sessionId))
        manager.cancel(attentionId(sessionId))
    }

    private fun post(id: Int, notification: android.app.Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(id, notification)
    }

    private fun openRoom(sessionId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            sessionId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROOM, sessionId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val PROGRESS = "room_progress"
        private const val ATTENTION = "room_attention"
        fun progressId(sessionId: Long) = 10_000 + sessionId.toInt()
        fun attentionId(sessionId: Long) = 20_000 + sessionId.toInt()
    }
}
