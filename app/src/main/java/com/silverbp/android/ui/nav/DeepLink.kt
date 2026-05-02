package com.silverbp.android.ui.nav

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single-shot deep-link bus used by notification taps. [com.silverbp.android.MainActivity]
 * writes a route (Coach tab or sub-route) read from the launching `Intent`,
 * and consumers in the nav graph re-act via `LaunchedEffect`.
 *
 * Why a SharedFlow with replay=0 (and not a Channel): the deep-link is
 * dispatched in two places —
 *   • [AppNavHost] navigates root-level sub-routes (weekly report, log screens)
 *   • [HomeWithTabs] switches the inner tab nav for `coach`
 * A Channel can only deliver each item to one consumer, so we'd lose the
 * event whichever side collected first. SharedFlow fan-outs to every
 * subscriber. replay=0 because we don't want to re-navigate when the
 * Compose tree restarts after a config change.
 *
 * `extraBufferCapacity = 4` covers tap bursts during slow nav transitions.
 */
internal object DeepLinkBus {
    private val _routes = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)

    fun emit(route: String) {
        _routes.tryEmit(route)
    }

    val routes: SharedFlow<String> = _routes.asSharedFlow()
}
