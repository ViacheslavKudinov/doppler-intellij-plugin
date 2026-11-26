package com.vk.doppler.toolwindow

import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Point
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.JViewport

class DopplerTreeTooltipIntegrationTest : BasePlatformTestCase() {
    fun testScrollingCancelsQueuedTreeTooltipAndAllowsHoverAgain() {
        val tree = JTree()
        val viewport = JViewport().apply { view = tree }
        tree.setSize(1_000, 1_000)
        dismissTreeTooltipOnScroll(tree, viewport)
        val manager = IdeTooltipManager.getInstance()
        val tooltip = IdeTooltip(tree, Point(20, 20), JLabel("Project description"))
        try {
            manager.show(tooltip, false)
            assertTrue(manager.isQueuedToShow(tooltip))
            assertTrue(manager.isProcessing(tree))
            viewport.viewPosition = Point(0, 100)
            assertFalse(manager.isQueuedToShow(tooltip))
            assertFalse(manager.isProcessing(tree))

            manager.show(tooltip, false)
            assertTrue("Hover must still be available after scrolling stops", manager.isQueuedToShow(tooltip))
            viewport.viewPosition = Point(50, 100)
            assertFalse(manager.isQueuedToShow(tooltip))
        } finally {
            manager.hideCurrentNow(false)
        }
    }

    fun testScrollingTreeDoesNotCancelAnotherComponentsTooltip() {
        val tree = JTree()
        val viewport = JViewport().apply { view = tree }
        tree.setSize(1_000, 1_000)
        dismissTreeTooltipOnScroll(tree, viewport)
        val manager = IdeTooltipManager.getInstance()
        val other = JLabel("Another control")
        val tooltip = IdeTooltip(other, Point(20, 20), JLabel("Other description"))
        try {
            manager.show(tooltip, false)
            assertFalse(manager.isProcessing(tree))
            viewport.viewPosition = Point(0, 100)
            assertTrue(manager.isQueuedToShow(tooltip))
            assertTrue(manager.isProcessing(other))
        } finally {
            manager.hideCurrentNow(false)
        }
    }
}
