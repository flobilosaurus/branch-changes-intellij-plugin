package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.model.ComparisonLimits
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason
import com.github.flobilosaurus.branchchanges.model.normalizedLineCount
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.commands.Git
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

interface BranchGitOperations {
    fun capture(
        repository: GitRepository,
        baseRef: String,
        mode: ComparisonMode,
    ): GitOperationResult<CapturedGitIdentity>

    fun loadBaseline(
        repository: GitRepository,
        baselineHash: String,
        relativePath: String,
        charset: Charset,
    ): GitOperationResult<BaselineContent>

    fun indexFingerprint(repository: GitRepository): GitOperationResult<String>

    fun loadNameStatus(repository: GitRepository, baselineHash: String): GitOperationResult<List<NameStatusRecord>> =
        GitOperationResult.Success(emptyList())
}

data class CapturedGitIdentity(
    val baseTipHash: String,
    val headHash: String,
    val baselineHash: String,
    val indexFingerprint: String,
)

data class BaselineContent(
    val text: String,
    val bytes: Int,
    val isNewFile: Boolean,
)

sealed interface GitOperationResult<out T> {
    data class Success<T>(val value: T) : GitOperationResult<T>
    data class Unavailable(
        val reason: ComparisonUnavailableReason,
        val detail: String? = null,
    ) : GitOperationResult<Nothing>
}

/** Sole native Git boundary used by branch comparisons. Every command is local and read-only. */
class BranchGitClient(
    private val project: Project,
    private val git: Git = Git.getInstance(),
) : BranchGitOperations {
    private val log = Logger.getInstance(BranchGitClient::class.java)

    override fun capture(
        repository: GitRepository,
        baseRef: String,
        mode: ComparisonMode,
    ): GitOperationResult<CapturedGitIdentity> {
        checkCancelled()
        val baseTip = resolveCommit(repository, baseRef)
            ?: return GitOperationResult.Unavailable(ComparisonUnavailableReason.INVALID_BASE)
        val head = resolveCommit(repository, "HEAD")
            ?: return GitOperationResult.Unavailable(ComparisonUnavailableReason.UNBORN_BRANCH)
        val index = when (val fingerprint = indexFingerprint(repository)) {
            is GitOperationResult.Success -> fingerprint.value
            is GitOperationResult.Unavailable -> return fingerprint
        }

        val baseline = when (mode) {
            ComparisonMode.BRANCH_HEAD -> baseTip
            ComparisonMode.FORK_POINT -> {
                val mergeBase = lineCommand(repository, GitCommand.MERGE_BASE, "--all", baseTip, head)
                checkCancelled()
                if (!mergeBase.success()) {
                    return GitOperationResult.Unavailable(
                        ComparisonUnavailableReason.NO_MERGE_BASE,
                        safeError(mergeBase.errorOutputAsJoinedString),
                    )
                }
                val hashes = mergeBase.output.map(String::trim).filter(String::isNotEmpty)
                if (hashes.any { !OBJECT_ID.matches(it) }) {
                    return GitOperationResult.Unavailable(ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT)
                }
                when (hashes.size) {
                    0 -> return GitOperationResult.Unavailable(ComparisonUnavailableReason.NO_MERGE_BASE)
                    1 -> hashes.single()
                    else -> return GitOperationResult.Unavailable(ComparisonUnavailableReason.MULTIPLE_MERGE_BASES)
                }
            }
        }
        checkCancelled()
        return GitOperationResult.Success(
            CapturedGitIdentity(baseTip, head, baseline, index),
        )
    }

    override fun loadBaseline(
        repository: GitRepository,
        baselineHash: String,
        relativePath: String,
        charset: Charset,
    ): GitOperationResult<BaselineContent> {
        checkCancelled()
        if (!OBJECT_ID.matches(baselineHash) || relativePath.isBlank()) {
            return GitOperationResult.Unavailable(ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT)
        }

        val objectSpec = "$baselineHash:$relativePath"
        val exists = lineCommand(repository, GitCommand.CAT_FILE, "-e", objectSpec)
        checkCancelled()
        if (!exists.success()) {
            val treeEntry = lineCommand(
                repository,
                GitCommand.LS_TREE,
                "--name-only",
                "-z",
                "--full-tree",
                baselineHash,
                "--",
                relativePath,
            )
            if (treeEntry.success() && treeEntry.output.any { it.trim('\u0000', ' ', '\t', '\r', '\n').isNotEmpty() }) {
                return GitOperationResult.Unavailable(ComparisonUnavailableReason.MISSING_OBJECT)
            }
            if (!treeEntry.success()) {
                return GitOperationResult.Unavailable(
                    ComparisonUnavailableReason.GIT_FAILURE,
                    safeError(treeEntry.errorOutputAsJoinedString),
                )
            }
            val ignored = lineCommand(repository, GitCommand.CHECK_IGNORE, "--quiet", "--no-index", "--", relativePath)
            checkCancelled()
            return when {
                ignored.success() -> GitOperationResult.Unavailable(ComparisonUnavailableReason.NEW_FILE_IGNORED)
                ignored.exitCode == 1 -> GitOperationResult.Success(BaselineContent("", 0, true))
                else -> GitOperationResult.Unavailable(
                    ComparisonUnavailableReason.GIT_FAILURE,
                    safeError(ignored.errorOutputAsJoinedString),
                )
            }
        }

        val sizeResult = lineCommand(repository, GitCommand.CAT_FILE, "-s", objectSpec)
        if (!sizeResult.success()) {
            return GitOperationResult.Unavailable(ComparisonUnavailableReason.GIT_FAILURE)
        }
        val size = sizeResult.output.singleOrNull()?.trim()?.toLongOrNull()
            ?: return GitOperationResult.Unavailable(ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT)
        if (size < 0) return GitOperationResult.Unavailable(ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT)
        if (size > ComparisonLimits.MAX_BYTES) {
            return GitOperationResult.Unavailable(ComparisonUnavailableReason.FILE_TOO_LARGE)
        }

        return try {
            val bytes = binaryCommand(repository, GitCommand.SHOW, objectSpec)
            checkCancelled()
            if (bytes.any { it == 0.toByte() }) {
                GitOperationResult.Unavailable(ComparisonUnavailableReason.BINARY)
            } else {
                val decoded = decode(bytes, charset)
                    ?: return GitOperationResult.Unavailable(ComparisonUnavailableReason.BINARY)
                val normalized = normalizeLineSeparators(decoded)
                if (normalizedLineCount(normalized) > ComparisonLimits.MAX_LINES) {
                    GitOperationResult.Unavailable(ComparisonUnavailableReason.TOO_MANY_LINES)
                } else {
                    GitOperationResult.Success(BaselineContent(normalized, bytes.size, false))
                }
            }
        } catch (error: VcsException) {
            log.warn("Branch Changes baseline blob read failed: root=${repository.root.path} path=$relativePath", error)
            GitOperationResult.Unavailable(
                if (error.message.orEmpty().contains("missing", ignoreCase = true)) {
                    ComparisonUnavailableReason.MISSING_OBJECT
                } else {
                    ComparisonUnavailableReason.GIT_FAILURE
                },
                safeError(error.message),
            )
        }
    }

    override fun loadNameStatus(
        repository: GitRepository,
        baselineHash: String,
    ): GitOperationResult<List<NameStatusRecord>> {
        if (!OBJECT_ID.matches(baselineHash)) {
            return GitOperationResult.Unavailable(ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT)
        }
        return try {
            val output = binaryCommand(
                repository,
                GitCommand.DIFF,
                "--cached",
                "--name-status",
                "-z",
                "-M",
                baselineHash,
                "--",
            )
            if (output.size > MAX_NAME_STATUS_BYTES) {
                return GitOperationResult.Unavailable(ComparisonUnavailableReason.FILE_TOO_LARGE)
            }
            when (val parsed = NameStatusParser.parse(output)) {
                is NameStatusParseResult.Success -> GitOperationResult.Success(parsed.records)
                is NameStatusParseResult.Failure -> GitOperationResult.Unavailable(
                    ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT,
                    "name-status offset ${parsed.offset}: ${parsed.reason}",
                )
            }
        } catch (error: VcsException) {
            log.warn("Branch Changes rename scan failed: root=${repository.root.path}", error)
            GitOperationResult.Unavailable(ComparisonUnavailableReason.GIT_FAILURE, safeError(error.message))
        }
    }

    override fun indexFingerprint(repository: GitRepository): GitOperationResult<String> {
        checkCancelled()
        val result = lineCommand(repository, GitCommand.REV_PARSE, "--git-path", "index")
        checkCancelled()
        if (!result.success()) {
            return GitOperationResult.Unavailable(
                ComparisonUnavailableReason.GIT_FAILURE,
                safeError(result.errorOutputAsJoinedString),
            )
        }
        val raw = result.output.singleOrNull()?.trim()
            ?: return GitOperationResult.Unavailable(ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT)
        val path = Path.of(raw).let { if (it.isAbsolute) it else Path.of(repository.root.path).resolve(it) }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = if (Files.exists(path)) Files.readAllBytes(path) else ByteArray(0)
            GitOperationResult.Success(digest.digest(bytes).toHex())
        } catch (error: Exception) {
            log.warn("Branch Changes index fingerprint failed: root=${repository.root.path}", error)
            GitOperationResult.Unavailable(ComparisonUnavailableReason.GIT_FAILURE, safeError(error.message))
        }
    }

    private fun resolveCommit(repository: GitRepository, ref: String): String? {
        val result = lineCommand(repository, GitCommand.REV_PARSE, "--verify", "--quiet", "--end-of-options", "$ref^{commit}")
        checkCancelled()
        if (!result.success()) return null
        return result.output.singleOrNull()?.trim()?.takeIf(OBJECT_ID::matches)
    }

    private fun lineCommand(
        repository: GitRepository,
        command: GitCommand,
        vararg parameters: String,
    ) = git.runCommand(
        GitLineHandler(project, repository.root, command).also {
            configure(it)
            it.addParameters(*parameters)
        },
    )

    private fun binaryCommand(
        repository: GitRepository,
        command: GitCommand,
        vararg parameters: String,
    ): ByteArray = GitBinaryHandler(project, repository.root, command).also {
        configure(it)
        it.addParameters(*parameters)
    }.run()

    private fun configure(handler: git4idea.commands.GitHandler) {
        handler.setSilent(true)
        handler.setEnableInteractiveCallbacks(false)
        handler.withNoTty()
        handler.addCustomEnvironmentVariable("GIT_NO_LAZY_FETCH", "1")
        handler.addCustomEnvironmentVariable("GIT_TERMINAL_PROMPT", "0")
        handler.addCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS", "0")
    }

    private fun checkCancelled() = ProgressManager.checkCanceled()

    private fun decode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun normalizeLineSeparators(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun safeError(value: String?): String? = value
        ?.lineSequence()
        ?.firstOrNull()
        ?.take(240)
        ?.takeIf(String::isNotBlank)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_NAME_STATUS_BYTES = 8 * 1024 * 1024
        val OBJECT_ID = Regex("[0-9a-fA-F]{40,64}")
    }
}
