package app.sunosongwriter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.sunosongwriter.App
import app.sunosongwriter.AppContainer

/** Creates a ViewModel from the app's [AppContainer] (manual DI; see docs/implementation-plan.md). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (container: AppContainer, savedState: SavedStateHandle) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as App).container
    return viewModel(factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } })
}
