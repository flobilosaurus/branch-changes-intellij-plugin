package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.event.MouseEvent

class BranchGutterRenderer(
    private val type: BranchChangeType,
    private val colorKey: TextAttributesKey,
    private val tooltip: String,
    private val action: ((Editor) -> Unit)? = null,
) : ActiveGutterRenderer {
    override fun paint(editor: Editor, graphics: Graphics, rectangle: Rectangle) {
        graphics.color = markerColor(editor)
        val width = JBUI.scale(3).coerceAtLeast(2)
        if (type == BranchChangeType.DELETED) {
            val size = JBUI.scale(5).coerceAtLeast(4)
            val x = rectangle.x + rectangle.width - size
            val y = rectangle.y
            graphics.fillPolygon(
                Polygon(
                    intArrayOf(x, x + size, x + size),
                    intArrayOf(y, y - size, y + size),
                    3,
                ),
            )
        } else {
            graphics.fillRect(rectangle.x + rectangle.width - width, rectangle.y, width, rectangle.height)
        }
    }

    override fun getTooltipText(): String = tooltip

    override fun getAccessibleName(): String = tooltip

    override fun getAccessibleTooltipText(): String = tooltip

    override fun canDoAction(editor: Editor, event: MouseEvent): Boolean = action != null && !editor.isDisposed

    override fun doAction(editor: Editor, event: MouseEvent) {
        if (!editor.isDisposed) action?.invoke(editor)
    }

    private fun markerColor(editor: Editor): Color {
        val attributes = editor.colorsScheme.getAttributes(colorKey)
        return attributes?.backgroundColor
            ?: attributes?.foregroundColor
            ?: editor.colorsScheme.defaultForeground
    }
}
