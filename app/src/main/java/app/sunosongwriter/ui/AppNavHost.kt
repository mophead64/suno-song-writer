package app.sunosongwriter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.sunosongwriter.App
import app.sunosongwriter.ui.backends.BackendEditScreen
import app.sunosongwriter.ui.backends.BackendListScreen
import app.sunosongwriter.ui.backends.PresetPickerScreen
import app.sunosongwriter.ui.handoff.HandoffScreen
import app.sunosongwriter.ui.library.FoldersScreen
import app.sunosongwriter.ui.library.LibraryScreen
import app.sunosongwriter.ui.library.SongDetailsScreen
import app.sunosongwriter.ui.participants.ParticipantEditScreen
import app.sunosongwriter.ui.participants.ParticipantListScreen
import app.sunosongwriter.ui.participants.StarterTeamScreen
import app.sunosongwriter.ui.playground.PlaygroundScreen
import app.sunosongwriter.ui.recipes.RecipeListScreen
import app.sunosongwriter.ui.recipes.RecipeScreen
import app.sunosongwriter.ui.results.IssueFixesScreen
import app.sunosongwriter.ui.results.ResultsScreen
import app.sunosongwriter.ui.results.WinRatesScreen
import app.sunosongwriter.ui.room.RoomScreen
import app.sunosongwriter.ui.room.RoomSetupScreen
import app.sunosongwriter.ui.settings.SettingsScreen
import app.sunosongwriter.ui.share.ShareScreen
import app.sunosongwriter.ui.templates.TemplateEditScreen
import app.sunosongwriter.ui.templates.TemplateListScreen
import app.sunosongwriter.ui.song.CompareScreen
import app.sunosongwriter.ui.song.SongScreen
import app.sunosongwriter.ui.start.GettingStartedScreen
import app.sunosongwriter.ui.usage.PricesScreen
import app.sunosongwriter.ui.usage.UsageScreen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * Goes back one screen, but only from a screen that's fully shown, and never past the library. A quick
 * double tap on a back arrow used to pop twice: the second tap landed while the first screen was still
 * animating away and popped the library too, leaving an empty (black) app.
 */
fun NavController.popIfSettled() {
    if (previousBackStackEntry == null) return
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    popBackStack()
}

/** The library is the home screen. */
@Serializable
data object LibraryRoute

@Serializable
data object FoldersRoute

/** Create a song ([songId] 0, optionally in [folderId]) or edit an existing one's details. */
@Serializable
data class SongDetailsRoute(val songId: Long = 0, val folderId: Long = 0)

/** [openTeam] opens the Team tab, e.g. to follow a step just started elsewhere. */
@Serializable
data class SongRoute(val songId: Long, val openTeam: Boolean = false)

/** [fromId] 0 compares against empty content. */
@Serializable
data class CompareRoute(val songId: Long, val fromId: Long, val toId: Long, val sideBySide: Boolean = false)

/** The results log for a sent snapshot (spec 5.12). */
@Serializable
data class ResultsRoute(val songId: Long, val snapshotId: Long)

@Serializable
data object RecipeListRoute

@Serializable
data class RecipeRoute(val recipeId: Long)

@Serializable
data object IssueFixesRoute

@Serializable
data object WinRatesRoute

@Serializable
data object GettingStartedRoute

@Serializable
data object UsageRoute

@Serializable
data object PricesRoute

@Serializable
data object SettingsRoute

@Serializable
data object BackendListRoute

@Serializable
data object PresetPickerRoute

/** Edit an existing backend ([backendId]), or create one from a preset ([presetId]). */
@Serializable
data class BackendEditRoute(val backendId: Long = 0, val presetId: String? = null)

@Serializable
data object ParticipantListRoute

@Serializable
data class ParticipantEditRoute(val participantId: Long = 0)

@Serializable
data object PlaygroundRoute

@Serializable
data object TemplateListRoute

/** [key] is a [app.sunosongwriter.domain.prompt.TemplateKey] value. */
@Serializable
data class TemplateEditRoute(val key: String)

/** Hand off a version ([versionId]) to Suno, or view a sent snapshot ([snapshotId]). */
@Serializable
data class HandoffRoute(val songId: Long, val versionId: Long = 0, val snapshotId: Long = 0)

/** Set up a Writers' Room run on [variantId]'s current version. */
@Serializable
data class RoomSetupRoute(val songId: Long, val variantId: Long)

@Serializable
data class RoomRoute(val sessionId: Long)

/** Builds the team from the backends already added: one participant each (spec 5.2). */
@Serializable
data object StarterTeamRoute

/** Text shared into the app; the text itself waits in [app.sunosongwriter.AppContainer.pendingShare]. */
@Serializable
data object ShareRoute

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popIfSettled() }
    val container = (LocalContext.current.applicationContext as App).container
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        if (pendingShare != null) nav.navigate(ShareRoute) { launchSingleTop = true }
    }
    val pendingRoom by container.pendingOpenRoom.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoom) {
        pendingRoom?.let {
            container.pendingOpenRoom.value = null
            nav.navigate(RoomRoute(it)) { launchSingleTop = true }
        }
    }
    // Guided setup opens by itself once, on a first launch with nothing set up.
    LaunchedEffect(Unit) {
        if (!container.settings.gettingStartedShown.first()) {
            container.settings.setGettingStartedShown()
            if (container.backends.backends.first().isEmpty() && container.pendingShare.value == null) nav.navigate(GettingStartedRoute)
        }
    }
    NavHost(navController = nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onOpenSong = { nav.navigate(SongRoute(it)) },
                onNewSong = { nav.navigate(SongDetailsRoute()) },
                onOpenFolders = { nav.navigate(FoldersRoute) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenRecipes = { nav.navigate(RecipeListRoute) },
                onOpenWinRates = { nav.navigate(WinRatesRoute) },
                onOpenGettingStarted = { nav.navigate(GettingStartedRoute) },
                onOpenParticipants = { nav.navigate(ParticipantListRoute) },
                onOpenNewSong = { nav.navigate(SongRoute(it, openTeam = true)) },
            )
        }
        composable<GettingStartedRoute> {
            GettingStartedScreen(
                onBack = back,
                onAddBackend = { nav.navigate(PresetPickerRoute) },
                onStarterTeam = { nav.navigate(StarterTeamRoute) },
                onOpenParticipants = { nav.navigate(ParticipantListRoute) },
                onNewSong = { nav.navigate(SongDetailsRoute()) },
                onOpenSong = { nav.navigate(SongRoute(it, openTeam = true)) },
            )
        }
        composable<FoldersRoute> {
            FoldersScreen(onBack = back)
        }
        composable<SongDetailsRoute> {
            SongDetailsScreen(
                onBack = back,
                onSaved = { saved ->
                    if (saved.created) {
                        nav.navigate(SongRoute(saved.id)) { popUpTo<SongDetailsRoute> { inclusive = true } }
                    } else {
                        nav.popIfSettled()
                    }
                },
                onDeleted = { nav.popBackStack(LibraryRoute, inclusive = false) },
            )
        }
        composable<SongRoute> {
            SongScreen(
                onBack = back,
                onOpenDetails = { nav.navigate(SongDetailsRoute(songId = it)) },
                onCompare = { songId, from, to -> nav.navigate(CompareRoute(songId, from, to)) },
                onSideBySide = { songId, from, to -> nav.navigate(CompareRoute(songId, from, to, sideBySide = true)) },
                onOpenParticipants = { nav.navigate(ParticipantListRoute) },
                onStartRoom = { songId, variantId -> nav.navigate(RoomSetupRoute(songId, variantId)) },
                onOpenRoom = { nav.navigate(RoomRoute(it)) },
                onHandoff = { songId, versionId -> nav.navigate(HandoffRoute(songId, versionId = versionId)) },
                onOpenResults = { songId, snapshotId -> nav.navigate(ResultsRoute(songId, snapshotId)) },
                onBuildTeam = { nav.navigate(StarterTeamRoute) },
            )
        }
        composable<HandoffRoute> {
            HandoffScreen(onBack = back, onOpenResults = { songId, snapshotId -> nav.navigate(ResultsRoute(songId, snapshotId)) })
        }
        composable<ResultsRoute> {
            ResultsScreen(
                onBack = back,
                onOpenSnapshot = { songId, snapshotId -> nav.navigate(HandoffRoute(songId, snapshotId = snapshotId)) },
                onOpenSong = { id -> nav.navigate(SongRoute(id, openTeam = true)) { popUpTo<SongRoute> { inclusive = true } } },
                onOpenRecipe = { nav.navigate(RecipeRoute(it)) },
            )
        }
        composable<RecipeListRoute> {
            RecipeListScreen(onBack = back, onOpen = { nav.navigate(RecipeRoute(it)) })
        }
        composable<RecipeRoute> {
            RecipeScreen(
                onBack = back,
                onOpenSong = { nav.navigate(SongRoute(it)) },
                onOpenRecipe = { nav.navigate(RecipeRoute(it)) },
                onNewSong = { id -> nav.navigate(SongRoute(id)) },
            )
        }
        composable<IssueFixesRoute> {
            IssueFixesScreen(onBack = back)
        }
        composable<WinRatesRoute> {
            WinRatesScreen(onBack = back)
        }
        composable<RoomSetupRoute> {
            RoomSetupScreen(
                onBack = back,
                onStarted = { id -> nav.navigate(RoomRoute(id)) { popUpTo<RoomSetupRoute> { inclusive = true } } },
                onOpenStarterTeam = { nav.navigate(StarterTeamRoute) },
            )
        }
        composable<RoomRoute> {
            RoomScreen(
                onBack = back,
                onOpenSong = { id -> nav.navigate(SongRoute(id)) { launchSingleTop = true } },
                onCompare = { songId, from, to -> nav.navigate(CompareRoute(songId, from, to)) },
                onHandoff = { songId, versionId -> nav.navigate(HandoffRoute(songId, versionId = versionId)) },
            )
        }
        composable<StarterTeamRoute> {
            StarterTeamScreen(
                onDone = back,
                onAddBackend = { nav.navigate(PresetPickerRoute) },
                onOpenBackend = { nav.navigate(BackendEditRoute(backendId = it)) },
            )
        }
        composable<CompareRoute> {
            CompareScreen(onBack = back)
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = back,
                onOpenBackends = { nav.navigate(BackendListRoute) },
                onOpenParticipants = { nav.navigate(ParticipantListRoute) },
                onOpenPlayground = { nav.navigate(PlaygroundRoute) },
                onOpenTemplates = { nav.navigate(TemplateListRoute) },
                onOpenIssueFixes = { nav.navigate(IssueFixesRoute) },
                onOpenUsage = { nav.navigate(UsageRoute) },
            )
        }
        composable<UsageRoute> {
            UsageScreen(onBack = back, onOpenPrices = { nav.navigate(PricesRoute) })
        }
        composable<PricesRoute> {
            PricesScreen(onBack = back)
        }
        composable<TemplateListRoute> {
            TemplateListScreen(onBack = back, onOpen = { nav.navigate(TemplateEditRoute(it)) })
        }
        composable<TemplateEditRoute> {
            TemplateEditScreen(onBack = back)
        }
        composable<ShareRoute> {
            ShareScreen(
                onDone = back,
                onOpenSong = { id -> nav.navigate(SongRoute(id)) { popUpTo<ShareRoute> { inclusive = true } } },
                onOpenRoom = { id -> nav.navigate(RoomRoute(id)) { popUpTo<ShareRoute> { inclusive = true } } },
                onEditNewSong = { id ->
                    nav.navigate(SongRoute(id)) { popUpTo<ShareRoute> { inclusive = true } }
                    nav.navigate(SongDetailsRoute(songId = id))
                },
                onOpenResults = { songId, snapshotId -> nav.navigate(ResultsRoute(songId, snapshotId)) { popUpTo<ShareRoute> { inclusive = true } } },
                onOpenNewSong = { id -> nav.navigate(SongRoute(id, openTeam = true)) { popUpTo<ShareRoute> { inclusive = true } } },
                onOpenParticipants = { nav.navigate(ParticipantListRoute) },
            )
        }
        composable<BackendListRoute> {
            BackendListScreen(
                onBack = back,
                onAdd = { nav.navigate(PresetPickerRoute) },
                onOpen = { nav.navigate(BackendEditRoute(backendId = it)) },
            )
        }
        composable<PresetPickerRoute> {
            PresetPickerScreen(
                onBack = back,
                onPick = { presetId ->
                    nav.navigate(BackendEditRoute(presetId = presetId)) {
                        popUpTo(PresetPickerRoute) { inclusive = true }
                    }
                },
            )
        }
        composable<BackendEditRoute> {
            BackendEditScreen(onBack = back)
        }
        composable<ParticipantListRoute> {
            ParticipantListScreen(
                onBack = back,
                onStarterTeam = { nav.navigate(StarterTeamRoute) },
                onAdd = { nav.navigate(ParticipantEditRoute()) },
                onOpen = { nav.navigate(ParticipantEditRoute(participantId = it)) },
            )
        }
        composable<ParticipantEditRoute> {
            ParticipantEditScreen(onBack = back)
        }
        composable<PlaygroundRoute> {
            PlaygroundScreen(onBack = back, onOpenParticipants = { nav.navigate(ParticipantListRoute) })
        }
    }
}
