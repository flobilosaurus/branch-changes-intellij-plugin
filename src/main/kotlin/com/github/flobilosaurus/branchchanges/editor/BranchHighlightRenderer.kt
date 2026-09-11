package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.model.BranchChangeRegion
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.util.IdentityHashMap

class BranchHighlightRenderer {
    private val owned = IdentityHashMap<Editor, List<RangeHighlighter>>()

    fun replace(editor: Editor, snapshot: ComparisonSnapshot, onClick: ((Editor) -> Unit)? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (editor.isDisposed) return
        val created = snapshot.regions.mapNotNull { region -> create(editor, snapshot, region, onClick) }
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
        onClick: ((Editor) -> Unit)?,
    ): RangeHighlighter? {
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
            if (region.currentStartLine >= lineCount) return null
            startOffset = document.getLineStartOffset(region.currentStartLine)
            endOffset = if (region.currentEndLine >= lineCount) {
                document.textLength
            } else {
                document.getLineStartOffset(region.currentEndLine)
            }
            targetArea = HighlighterTargetArea.LINES_IN_RANGE
        }
        val key = colorKey(region.type)
        val tooltip = BranchChangesBundle.message(
            "marker.tooltip",
            typeText(region.type),
            displayRef(snapshot.identity.baseRef),
            snapshot.identity.forkHash.take(7),
        )
        return editor.markupModel.addRangeHighlighter(
            key,
            startOffset,
            endOffset,
            HighlighterLayer.ADDITIONAL_SYNTAX + 1,
            targetArea,
        ).also {
            it.lineMarkerRenderer = BranchGutterRenderer(region.type, key, tooltip, onClick)
            it.errorStripeTooltip = tooltip
        }
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
