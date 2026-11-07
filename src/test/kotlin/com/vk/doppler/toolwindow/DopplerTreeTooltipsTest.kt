package com.vk.doppler.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities

class DopplerTreeTooltipsTest {
    @Test
    fun `scrolling in either direction dismisses hover without mouse movement`() {
        SwingUtilities.invokeAndWait {
            val tree = JTree()
            val viewport = JViewport().apply { view = tree }
            tree.setSize(1_000, 1_000)
            var dismissals = 0
            dismissTreeTooltipOnScroll(tree, viewport) { dismissals++ }

            for ((index, position) in listOf(Point(0, 100), Point(0, 200), Point(0, 100), Point(50, 100), Point(0, 0)).withIndex()) {
                viewport.viewPosition = position
                assertEquals(index + 1, dismissals)
            }
        }
    }

    @Test
    fun `viewport size changes and unchanged positions do not dismiss hover`() {
        SwingUtilities.invokeAndWait {
            val tree = JTree()
            val viewport = JViewport().apply { view = tree }
            tree.setSize(1_000, 1_000)
            var dismissals = 0
            dismissTreeTooltipOnScroll(tree, viewport) { dismissals++ }

            viewport.extentSize = Dimension(200, 200)
            viewport.viewPosition = Point(0, 0)
            tree.setSize(1_200, 1_200)
            assertEquals(0, dismissals)
            viewport.viewPosition = Point(0, 100)
            assertEquals(1, dismissals)
            viewport.extentSize = Dimension(300, 300)
            viewport.viewPosition = Point(0, 100)
            assertEquals(1, dismissals)
        }
    }
}
