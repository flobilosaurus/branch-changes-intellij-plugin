package com.github.flobilosaurus.branchchanges.git

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class NameStatusKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
}

data class NameStatusRecord(
    val kind: NameStatusKind,
    val path: String,
    val sourcePath: String? = null,
    val similarity: Int? = null,
)

sealed interface NameStatusParseResult {
    data class Success(val records: List<NameStatusRecord>) : NameStatusParseResult
    data class Failure(val offset: Int, val reason: String) : NameStatusParseResult
}

object NameStatusParser {
    fun parse(
        output: ByteArray,
        charset: Charset = Charsets.UTF_8,
    ): NameStatusParseResult {
        if (output.isEmpty()) return NameStatusParseResult.Success(emptyList())
        val fields = mutableListOf<Pair<Int, String>>()
        var start = 0
        for (index in output.indices) {
            if (output[index] != 0.toByte()) continue
            val value = decode(output, start, index - start, charset)
                ?: return NameStatusParseResult.Failure(start, "invalid path encoding")
            fields += start to value
            start = index + 1
        }
        if (start != output.size) return NameStatusParseResult.Failure(start, "truncated NUL-delimited record")

        val records = mutableListOf<NameStatusRecord>()
        var index = 0
        while (index < fields.size) {
            val (statusOffset, status) = fields[index++]
            if (status.isEmpty()) return NameStatusParseResult.Failure(statusOffset, "empty status")
            fun nextPath(label: String): Pair<Int, String>? {
                if (index >= fields.size) return null
                return fields[index++]
            }

            when {
                status == "A" -> {
                    val path = nextPath("path") ?: return missing(statusOffset, "added path")
                    if (path.second.isEmpty()) return emptyPath(path.first)
                    records += NameStatusRecord(NameStatusKind.ADDED, path.second)
                }
                status == "M" -> {
                    val path = nextPath("path") ?: return missing(statusOffset, "modified path")
                    if (path.second.isEmpty()) return emptyPath(path.first)
                    records += NameStatusRecord(NameStatusKind.MODIFIED, path.second)
                }
                status == "D" -> {
                    val path = nextPath("path") ?: return missing(statusOffset, "deleted path")
                    if (path.second.isEmpty()) return emptyPath(path.first)
                    records += NameStatusRecord(NameStatusKind.DELETED, path.second)
                }
                RENAME.matches(status) || COPY.matches(status) -> {
                    val similarity = status.drop(1).toInt()
                    val source = nextPath("source") ?: return missing(statusOffset, "source path")
                    val destination = nextPath("destination") ?: return missing(statusOffset, "destination path")
                    if (source.second.isEmpty()) return emptyPath(source.first)
                    if (destination.second.isEmpty()) return emptyPath(destination.first)
                    records += NameStatusRecord(
                        if (status[0] == 'R') NameStatusKind.RENAMED else NameStatusKind.COPIED,
                        destination.second,
                        source.second,
                        similarity,
                    )
                }
                else -> return NameStatusParseResult.Failure(statusOffset, "unsupported status '$status'")
            }
        }
        return NameStatusParseResult.Success(records)
    }

    private fun decode(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun missing(offset: Int, what: String) =
        NameStatusParseResult.Failure(offset, "missing $what")

    private fun emptyPath(offset: Int) = NameStatusParseResult.Failure(offset, "empty path")

    private val RENAME = Regex("R(?:00[1-9]|0[1-9][0-9]|100)")
    private val COPY = Regex("C(?:00[1-9]|0[1-9][0-9]|100)")
}
