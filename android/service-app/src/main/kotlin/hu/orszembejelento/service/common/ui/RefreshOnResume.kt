package hu.orszembejelento.service.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Field-test fix: an operational screen's ViewModel loads once, in its own `init {}`, but
 * nothing made it reload when the user came back to that same screen - most visibly, when
 * returning from a detail screen (claim/return/close/restore/edit) via the in-app back arrow
 * or the system Back gesture, the underlying list kept showing whatever it last held.
 *
 * Call this once at the top of a `composable(route) { ... }` destination. Inside a
 * [androidx.navigation.compose.NavHost], each destination's content is composed under a
 * [androidx.lifecycle.LifecycleOwner] tied to its own `NavBackStackEntry`: that entry's
 * lifecycle reaches `RESUMED` on the very first composition, drops to `STARTED` (never lower,
 * it stays on the back stack) while a screen pushed on top of it is showing, and returns to
 * `RESUMED` the moment the user comes back to it - by popping the top screen, or by switching
 * bottom-navigation tabs back to it. [onResume] runs on every one of those transitions, so it
 * is a real "became active again" signal - never a per-frame recomposition, and never a timer
 * (no background polling is introduced by this).
 *
 * [onResume] itself must be safe to call more than it strictly needs to be (for example, it
 * also fires once on that very first composition, alongside the ViewModel's own `init`-time
 * load) - see each affected ViewModel's own `refresh()`, which cancels any refresh already in
 * flight before starting a new one, so an accidental extra call here can race a stale response
 * against a fresh one, never show one.
 */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResume = rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnResume.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
