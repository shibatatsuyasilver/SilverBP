package com.silverbp.android.ui.nav

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single-shot deep-link bus used by notification taps. [com.silverbp.android.MainActivity]
 * writes a route (Coach tab or sub-route) read from the launching `Intent`,
 * and consumers in the nav graph re-act via `LaunchedEffect`.
 *
 * Why a SharedFlow (and not a Channel): the deep-link is dispatched in two
 * places —
 *   • [AppNavHost] navigates root-level sub-routes (weekly report, log screens)
 *   • [HomeWithTabs] switches the inner tab nav for `coach`
 * A Channel can only deliver each item to one consumer, so we'd lose the
 * event whichever side collected first. SharedFlow fan-outs to every
 * subscriber.
 *
 * Why replay = 1 (issue #26): a notification cold start emits the route from
 * `MainActivity.onCreate`, BEFORE the NavHost's `LaunchedEffect` collector has
 * subscribed. With replay = 0 that first event was silently dropped and the
 * deep link lost. replay = 1 retains the last route so the late (or
 * gate-deferred) subscriber still receives it. `onBufferOverflow = DROP_OLDEST`
 * keeps [emit] non-blocking and makes the newest tap win during bursts.
 *
 * To avoid re-navigating when the Compose tree restarts (e.g. config change),
 * the handling collector calls [consume] right after it navigates — this clears
 * the replay cache so a later re-subscribe does NOT replay an already-handled
 * route. `extraBufferCapacity = 4` still covers tap bursts during slow nav.
 */
internal object DeepLinkBus {
    private val _routes = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun emit(route: String) {
        _routes.tryEmit(route)
    }

    /**
     * Clear the retained route after a collector has navigated, so a later
     * recomposition (config change) that re-subscribes does not replay and
     * re-navigate to an already-consumed deep link.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consume() {
        _routes.resetReplayCache()
    }

    val routes: SharedFlow<String> = _routes.asSharedFlow()
}
