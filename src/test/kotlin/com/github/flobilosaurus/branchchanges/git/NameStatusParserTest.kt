package com.github.flobilosaurus.branchchanges.git

import junit.framework.TestCase

class NameStatusParserTest : TestCase() {
    fun testParsesBasicAndRenameSimilarityRange() {
        val parsed = success(fields("A", "add.txt", "M", "mod.txt", "D", "del.txt", "R100", "old", "new", "R001", "a", "b"))
        assertEquals(
            listOf(NameStatusKind.ADDED, NameStatusKind.MODIFIED, NameStatusKind.DELETED, NameStatusKind.RENAMED, NameStatusKind.RENAMED),
            parsed.map { it.kind },
        )
        assertEquals("old", parsed[3].sourcePath)
        assertEquals("new", parsed[3].path)
        assertEquals(100, parsed[3].similarity)
        assertEquals(1, parsed[4].similarity)
    }

    fun testPreservesWhitespaceNewlineAndUnicodePaths() {
        val records = success(fields("R087", " old\n name ", "新しい 名.txt"))
        assertEquals(" old\n name ", records.single().sourcePath)
        assertEquals("新しい 名.txt", records.single().path)
    }

    fun testCopyIsTypedSeparatelyForAdditionFallback() {
        val record = success(fields("C100", "source", "copy")).single()
        assertEquals(NameStatusKind.COPIED, record.kind)
        assertEquals("source", record.sourcePath)
        assertEquals("copy", record.path)
    }

    fun testRejectsMalformedAndTruncatedOutput() {
        assertTrue(NameStatusParser.parse("R100\u0000old\u0000".toByteArray()) is NameStatusParseResult.Failure)
        assertTrue(NameStatusParser.parse("M\u0000path".toByteArray()) is NameStatusParseResult.Failure)
        assertTrue(NameStatusParser.parse(fields("R000", "old", "new")) is NameStatusParseResult.Failure)
        assertTrue(NameStatusParser.parse(fields("X", "path")) is NameStatusParseResult.Failure)
        assertTrue(NameStatusParser.parse(fields("A", "")) is NameStatusParseResult.Failure)
    }

    private fun fields(vararg values: String): ByteArray =
        (values.joinToString("\u0000") + "\u0000").toByteArray()

    private fun success(bytes: ByteArray): List<NameStatusRecord> =
        (NameStatusParser.parse(bytes) as NameStatusParseResult.Success).records
}
