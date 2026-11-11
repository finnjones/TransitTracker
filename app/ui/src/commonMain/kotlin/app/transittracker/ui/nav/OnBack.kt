package app.transittracker.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** A page's own answer to back, for the page shown as navigator entry [entryId]. */
internal class PageBackHandler(val entryId: Long, val onBack: () -> Unit)

/**
 * While [enabled], back on this page (system back, swipe back, or anything that calls
 * `Navigator.back()`) runs [onBack] instead of leaving the page. Use it when the page shows
 * something of its own that back should close first: a search view, a sub-page, a picker.
 *
 * ```
 * OnBack(enabled = state.editing != null) { holder.closeSearch() }
 * ```
 *
 * [onBack] must close that thing itself and must not call `Navigator.back()`. Only the page on
 * screen is asked; a handler belongs to the page that was current when it was set up.
 */
@Composable
fun OnBack(enabled: Boolean = true, onBack: () -> Unit) {
    val nav = LocalNavigator.current
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(nav, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val handler = PageBackHandler(nav.current.id) { latest() }
        nav.addBackHandler(handler)
        onDispose { nav.removeBackHandler(handler) }
    }
}
