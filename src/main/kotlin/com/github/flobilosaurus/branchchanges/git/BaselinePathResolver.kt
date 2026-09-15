package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason
import git4idea.repo.GitRepository

sealed interface BaselinePathResult {
    data class Success(
        val baselinePath: String,
        val renamed: Boolean,
    ) : BaselinePathResult

    data class Unavailable(
        val reason: ComparisonUnavailableReason,
        val detail: String? = null,
    ) : BaselinePathResult
}

class BaselinePathResolver(
    private val git: BranchGitOperations,
    private val maxEntries: Int = 8,
) {
    private data class CacheKey(
        val rootUrl: String,
        val baselineHash: String,
        val indexFingerprint: String,
        val renameThreshold: Int,
    )

    private val cache = object : LinkedHashMap<CacheKey, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Map<String, String>>): Boolean =
            size > maxEntries
    }

    fun resolve(
        repository: GitRepository,
        rootUrl: String,
        baselineHash: String,
        indexFingerprint: String,
        currentPath: String,
        renameThreshold: Int = DEFAULT_RENAME_THRESHOLD,
    ): BaselinePathResult {
        val key = CacheKey(rootUrl, baselineHash, indexFingerprint, renameThreshold)
        val renameMap = synchronized(cache) { cache[key] } ?: when (val loaded = git.loadNameStatus(repository, baselineHash)) {
            is GitOperationResult.Success -> loaded.value
                .asSequence()
                .filter { it.kind == NameStatusKind.RENAMED }
                .associate { it.path to requireNotNull(it.sourcePath) }
                .also { synchronized(cache) { cache[key] = it } }
            is GitOperationResult.Unavailable -> return BaselinePathResult.Unavailable(loaded.reason, loaded.detail)
        }
        return renameMap[currentPath]?.let { BaselinePathResult.Success(it, true) }
            ?: BaselinePathResult.Success(currentPath, false)
    }

    fun clear() = synchronized(cache) { cache.clear() }

    internal fun cacheSize(): Int = synchronized(cache) { cache.size }

    companion object {
        const val DEFAULT_RENAME_THRESHOLD = 50
    }
}
