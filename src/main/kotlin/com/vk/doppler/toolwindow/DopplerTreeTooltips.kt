package com.vk.doppler.toolwindow

import com.intellij.ide.IdeTooltipManager
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.ToolTipManager

/** A stationary pointer does not generate mouse-exit events when the rows beneath it scroll. */
internal fun dismissTreeTooltipOnScroll(
    tree: JTree,
    viewport: JViewport,
    dismiss: () -> Unit = {
        val manager = IdeTooltipManager.getInstance()
        if (manager.isProcessing(tree)) manager.hideCurrentNow(false)
        ToolTipManager.sharedInstance().mouseExited(MouseEvent(tree, MouseEvent.MOUSE_EXITED, 0, 0, -1, -1, 0, false))
    },
) {
    var position = viewport.viewPosition
    // The listener is owned by the tree's viewport, so it has the same lifetime as the panel.
    viewport.addChangeListener {
        val next = viewport.viewPosition
        if (next != position) {
            position = next
            dismiss()
        }
    }
}
