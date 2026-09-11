package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class BranchChangesColors : ColorSettingsPage {
    override fun getDisplayName(): String = BranchChangesBundle.message("colors.displayName")

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(BranchChangesBundle.message("colors.added"), ADDED),
        AttributesDescriptor(BranchChangesBundle.message("colors.modified"), MODIFIED),
        AttributesDescriptor(BranchChangesBundle.message("colors.deleted"), DELETED),
    )

    override fun getColorDescriptors() = com.intellij.openapi.options.colors.ColorDescriptor.EMPTY_ARRAY

    override fun getDemoText(): String = "<added>added line</added>\n<modified>modified line</modified>\n<deleted>deletion anchor</deleted>"

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "added" to ADDED,
        "modified" to MODIFIED,
        "deleted" to DELETED,
    )

    companion object {
        @JvmField
        val ADDED: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "BRANCH_CHANGES_ADDED",
            DiffColors.DIFF_INSERTED,
        )

        @JvmField
        val MODIFIED: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "BRANCH_CHANGES_MODIFIED",
            DiffColors.DIFF_MODIFIED,
        )

        @JvmField
        val DELETED: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "BRANCH_CHANGES_DELETED",
            DiffColors.DIFF_DELETED,
        )
    }
}
