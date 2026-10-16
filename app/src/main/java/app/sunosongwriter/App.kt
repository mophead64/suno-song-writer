package app.sunosongwriter

import android.app.Application
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.watchRooms()
        container.appScope.launch {
            // Writers' Rooms that were mid-run when the process died carry on (WorkManager also retries its own work).
            container.sessions.rooms().filter { !container.room.state(it).stage.isOver && container.room.state(it).pause == null }
                .forEach { container.roomScheduler.kick(it.id) }
            // Steps that were streaming when the process died can't resume yet (iteration 4); say so.
            container.sessions.failInterrupted()
            // Spec 5.2 (model churn): refresh model lists on launch; unreachable backends are skipped.
            container.backends.refreshAllModels()
            container.requestCounts.pruneOlderThan(days = 7)
        }
    }
}
