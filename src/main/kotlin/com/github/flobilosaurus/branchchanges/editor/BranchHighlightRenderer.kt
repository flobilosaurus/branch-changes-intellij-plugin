package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.model.BranchChangeRegion
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.util.IdentityHashMap

class BranchHighlightRenderer {
    private val owned = IdentityHashMap<Editor, List<RangeHighlighter>>()

    fun replace(editor: Editor, snapshot: ComparisonSnapshot, onClick: ((Editor, Int) -> Unit)? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (editor.isDisposed) return
        val created = snapshot.regions.flatMap { region -> create(editor, snapshot, region, onClick) }
        val previous = owned.put(editor, created).orEmpty()
        previous.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
    }

    fun clear(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val highlighters = owned.remove(editor).orEmpty()
        if (!editor.isDisposed) {
            highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        }
    }

    fun clearAll() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        owned.keys.toList().forEach(::clear)
        owned.clear()
    }

    internal fun ownedHighlighters(editor: Editor): List<RangeHighlighter> = owned[editor].orEmpty()

    private fun create(
        editor: Editor,
        snapshot: ComparisonSnapshot,
        region: BranchChangeRegion,
        onClick: ((Editor, Int) -> Unit)?,
    ): List<RangeHighlighter> {
        val document = editor.document
        val lineCount = document.lineCount
        val startOffset: Int
        val endOffset: Int
        val targetArea: HighlighterTargetArea
        if (region.type == BranchChangeType.DELETED) {
            startOffset = if (region.anchorLine >= lineCount) document.textLength else document.getLineStartOffset(region.anchorLine)
            endOffset = startOffset
            targetArea = HighlighterTargetArea.EXACT_RANGE
        } else {
            if (region.currentStartLine >= lineCount) return emptyList()
            startOffset = document.getLineStartOffset(region.currentStartLine)
            endOffset = document.getLineEndOffset(region.currentEndLine - 1)
            targetArea = HighlighterTargetArea.LINES_IN_RANGE
        }
        val key = colorKey(region.type)
        val tooltip = BranchChangesBundle.message(
            "marker.tooltip",
            typeText(region.type),
            snapshot.identity.mode.displayName(),
            snapshot.identity.baselineHash.take(7),
            displayRef(snapshot.identity.baseRef),
        )
        val lineHighlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.ADDITIONAL_SYNTAX + 1,
            lineAttributes(editor, key),
            targetArea,
        ).also {
            it.lineMarkerRenderer = BranchGutterRenderer(region.type, key, tooltip, onClick)
            it.errorStripeTooltip = tooltip
        }
        val inlineHighlighters = region.changedSpans.mapNotNull { span ->
            val spanStart = span.currentStartOffset.coerceIn(0, document.textLength)
            val spanEnd = span.currentEndOffset.coerceIn(spanStart, document.textLength)
            if (spanStart == spanEnd) return@mapNotNull null
            editor.markupModel.addRangeHighlighter(
                spanStart,
                spanEnd,
                HighlighterLayer.ADDITIONAL_SYNTAX + 2,
                inlineAttributes(editor, key),
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
        return listOf(lineHighlighter) + inlineHighlighters
    }

    private fun lineAttributes(editor: Editor, key: TextAttributesKey): TextAttributes {
        val configured = editor.colorsScheme.getAttributes(key)
        val strong = configured?.backgroundColor ?: editor.colorsScheme.defaultForeground
        val light = configured?.foregroundColor
            ?: ColorUtil.mix(strong, editor.colorsScheme.defaultBackground, 0.6)
        return TextAttributes().apply {
            backgroundColor = light
            errorStripeColor = configured?.errorStripeColor ?: strong
        }
    }

    private fun inlineAttributes(editor: Editor, key: TextAttributesKey): TextAttributes {
        val configured = editor.colorsScheme.getAttributes(key)
        val strong = configured?.backgroundColor ?: editor.colorsScheme.defaultForeground
        return TextAttributes().apply { backgroundColor = strong }
    }

    private fun colorKey(type: BranchChangeType) = when (type) {
        BranchChangeType.ADDED -> BranchChangesColors.ADDED
        BranchChangeType.MODIFIED -> BranchChangesColors.MODIFIED
        BranchChangeType.DELETED -> BranchChangesColors.DELETED
    }

    private fun typeText(type: BranchChangeType): String = BranchChangesBundle.message(
        when (type) {
            BranchChangeType.ADDED -> "marker.added"
            BranchChangeType.MODIFIED -> "marker.modified"
            BranchChangeType.DELETED -> "marker.deleted"
        },
    )

    private fun displayRef(ref: String): String = ref
        .removePrefix("refs/remotes/")
        .removePrefix("refs/heads/")
}
