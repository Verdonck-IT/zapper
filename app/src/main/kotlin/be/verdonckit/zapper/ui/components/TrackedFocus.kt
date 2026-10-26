package be.verdonckit.zapper.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Een [FocusRequester] die weet of hij aan een element hangt. Compose gooit een IllegalStateException
 * ("FocusRequester is not initialized") als een focusRestorer-fallback naar een requester zonder element
 * wijst, bv. de eerste kaart van een rij die weggescrold of net vervangen is ("Verder kijken" herschikt
 * na het kijken). Dat crashte de app.
 */
class TrackedFocusRequester {
    val requester = FocusRequester()
    internal var attachedNodes = 0
    val isAttached: Boolean get() = attachedNodes > 0

    /** [requester] als die aan een element hangt, anders de gewone focuszoektocht van Compose. */
    fun orDefault(): FocusRequester = if (isAttached) requester else FocusRequester.Default
}

/** Zoals [focusRequester], en houdt bij of het element er is (attach/detach op hetzelfde moment). */
fun Modifier.focusRequester(tracked: TrackedFocusRequester): Modifier =
    focusRequester(tracked.requester).then(AttachTrackerElement(tracked))

private class AttachTrackerElement(private val tracked: TrackedFocusRequester) : ModifierNodeElement<AttachTrackerNode>() {
    override fun create() = AttachTrackerNode(tracked)

    override fun update(node: AttachTrackerNode) {
        if (node.tracked === tracked) return
        if (node.isAttached) {
            node.tracked.attachedNodes--
            tracked.attachedNodes++
        }
        node.tracked = tracked
    }

    override fun equals(other: Any?) = other is AttachTrackerElement && other.tracked === tracked
    override fun hashCode() = System.identityHashCode(tracked)
}

private class AttachTrackerNode(var tracked: TrackedFocusRequester) : Modifier.Node() {
    override fun onAttach() { tracked.attachedNodes++ }
    override fun onDetach() { tracked.attachedNodes-- }
}
