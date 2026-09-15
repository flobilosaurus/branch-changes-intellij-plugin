package com.github.flobilosaurus.branchchanges

import com.github.flobilosaurus.branchchanges.diff.BranchLineComparator
import com.github.flobilosaurus.branchchanges.diff.LineComparisonResult
import com.github.flobilosaurus.branchchanges.editor.BranchHighlightRenderer
import com.github.flobilosaurus.branchchanges.editor.ShowBranchDiffAction
import com.github.flobilosaurus.branchchanges.git.BranchContextResolver
import com.github.flobilosaurus.branchchanges.git.BaselinePathResolver
import com.github.flobilosaurus.branchchanges.git.BaselinePathResult
import com.github.flobilosaurus.branchchanges.git.BranchGitClient
import com.github.flobilosaurus.branchchanges.git.BranchGitOperations
import com.github.flobilosaurus.branchchanges.git.BranchRefProvider
import com.github.flobilosaurus.branchchanges.git.GitBranchRefProvider
import com.github.flobilosaurus.branchchanges.git.GitOperationResult
import com.github.flobilosaurus.branchchanges.model.BranchContext
import com.github.flobilosaurus.branchchanges.model.ComparisonIdentity
import com.github.flobilosaurus.branchchanges.model.ComparisonLimits
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.github.flobilosaurus.branchchanges.model.ComparisonResult
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason
import com.github.flobilosaurus.branchchanges.model.TextMetadata
import com.github.flobilosaurus.branchchanges.model.normalizedLineCount
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.github.flobilosaurus.branchchanges.ui.BranchBaseChooser
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import java.nio.charset.Charset
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class BranchChangesProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    sealed interface UiState {
        data object Disabled : UiState
        data object NoFile : UiState
        data class SelectBase(val context: BranchContext) : UiState
        data class Base(
            val context: BranchContext,
            val baseRef: String,
            val mode: ComparisonMode,
        ) : UiState
        data class ComparisonUnavailable(
            val context: BranchContext,
            val baseRef: String,
            val mode: ComparisonMode,
            val reason: ComparisonUnavailableReason,
        ) : UiState
        data class Unavailable(val reason: BranchContextResolver.UnavailableReason) : UiState
        data class InvalidBase(val context: BranchContext, val baseRef: String) : UiState
    }

    fun interface UiListener {
        fun stateChanged()
    }

    private data class MappingKey(val rootUrl: String, val branchName: String)

    private data class BaselineConfigurationIdentity(
        val rootUrl: String,
        val relativePath: String,
        val branchName: String,
        val baseRef: String,
        val mode: ComparisonMode,
        val headHash: String,
        val baseTipHash: String?,
    )

    private enum class SnapshotValidity {
        CURRENT,
        STALE,
        UNCERTAIN,
    }

    private data class ComparisonRequest(
        val document: Document,
        val file: VirtualFile,
        val context: BranchContext,
        val baseRef: String,
        val mode: ComparisonMode,
        val text: String,
        val charset: Charset,
        val documentStamp: Long,
        val generation: Long,
    )

    private class DocumentEntry(
        val document: Document,
        val editors: MutableSet<Editor>,
        val listener: DocumentListener,
    ) {
        var generation: Long = 0
        var job: Job? = null
        var lastResult: ComparisonResult? = null
        var lastConfigurationIdentity: BaselineConfigurationIdentity? = null
        var validatedIndexIdentity: String? = null
    }

    private val log = Logger.getInstance(BranchChangesProjectService::class.java)
    private val settings = BranchBaseSettings.getInstance(project)
    private val contextResolver = BranchContextResolver(project, settings)
    private val refProvider: BranchRefProvider = GitBranchRefProvider(project)
    private val chooser = BranchBaseChooser(project, refProvider, coroutineScope)
    internal var chooseBase: (
        BranchContext,
        (BranchRefProvider.Validation.Valid) -> Unit,
    ) -> Unit = chooser::choose
    private val initialized = AtomicBoolean(false)
    private val validationGeneration = AtomicLong()
    private val invalidBases = ConcurrentHashMap<MappingKey, String>()
    private val warnedFailures = ConcurrentHashMap.newKeySet<String>()
    private val documentEntries = IdentityHashMap<Document, DocumentEntry>()
    private val renderer = BranchHighlightRenderer()
    private val showDiffAction = ShowBranchDiffAction()
    internal var presentDiff: (Project, Document, VirtualFile, ComparisonSnapshot, Int) -> Unit = showDiffAction::show
    private val pendingDiffLines = IdentityHashMap<Editor, Int>()

    @Volatile
    internal var gitOperations: BranchGitOperations = BranchGitClient(project)
        set(value) {
            field = value
            baselinePathResolver = BaselinePathResolver(value)
        }

    @Volatile
    private var baselinePathResolver = BaselinePathResolver(gitOperations)

    @Volatile
    internal var lineComparator: BranchLineComparator = BranchLineComparator()

    @Volatile
    private var currentFileSnapshot: VirtualFile? = null

    @Volatile
    internal var disposed: Boolean = false
        private set

    fun initialize() {
        if (!initialized.compareAndSet(false, true) || project.isDisposed) return

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            DynamicPluginListener.TOPIC,
            object : DynamicPluginListener {
                override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                    this@BranchChangesProjectService.beforePluginUnload(pluginDescriptor.pluginId.idString)
                }
            },
        )
        project.messageBus.connect(this).apply {
            subscribe(BranchBaseSettings.TOPIC, BranchBaseSettings.Listener { refresh("settings") })
            subscribe(GitRepository.GIT_REPO_CHANGE, git4idea.repo.GitRepositoryChangeListener { refresh("repository") })
            subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refresh("color-scheme") })
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any {
                            it is VFileMoveEvent ||
                                (it is VFilePropertyChangeEvent && it.propertyName == VirtualFile.PROP_NAME)
                        }
                    ) refresh("vfs")
                }
            })
        }
        runOnEdt {
            EditorFactory.getInstance().allEditors
                .filter { it.project === project }
                .forEach(::registerEditor)
            refresh("startup")
        }
    }

    fun uiState(file: VirtualFile? = currentFile()): UiState {
        if (!settings.isEnabled()) return UiState.Disabled
        if (file == null || !file.isValid) return UiState.NoFile

        return when (val resolved = contextResolver.resolve(file)) {
            is BranchContextResolver.Result.Unavailable -> UiState.Unavailable(resolved.reason)
            is BranchContextResolver.Result.Available -> {
                val context = resolved.context
                val base = context.configuredBaseRef ?: return UiState.SelectBase(context)
                val key = MappingKey(context.rootUrl, context.branchName)
                if (invalidBases[key] == base) return UiState.InvalidBase(context, base)
                val document = FileDocumentManager.getInstance().getCachedDocument(file)
                val unavailable = document?.let { documentEntries[it]?.lastResult as? ComparisonResult.Unavailable }
                if (unavailable?.reason == ComparisonUnavailableReason.INVALID_BASE) {
                    UiState.InvalidBase(context, base)
                } else if (unavailable != null && unavailable.reason !in TRANSIENT_UI_REASONS) {
                    UiState.ComparisonUnavailable(context, base, context.comparisonMode, unavailable.reason)
                } else {
                    UiState.Base(context, base, context.comparisonMode)
                }
            }
        }
    }

    fun selectBase(file: VirtualFile? = currentFile()) {
        if (project.isDisposed || file == null) return
        val result = contextResolver.resolve(file)
        if (result !is BranchContextResolver.Result.Available) return
        val context = result.context

        chooseBase(context) selection@ { selected ->
            if (project.isDisposed) return@selection
            val current = contextResolver.resolve(file)
            if (current !is BranchContextResolver.Result.Available ||
                current.context.rootUrl != context.rootUrl ||
                current.context.branchName != context.branchName
            ) return@selection
            val previous = settings.getBase(context.rootUrl, context.branchName)
            settings.setBase(context.rootUrl, context.branchName, selected.fullName)
            invalidBases.remove(MappingKey(context.rootUrl, context.branchName))
            if (previous == selected.fullName) refresh("base-reselected")
        }
    }

    fun selectMode(mode: ComparisonMode, file: VirtualFile? = currentFile()) {
        if (project.isDisposed || file == null) return
        val result = contextResolver.resolve(file)
        if (result !is BranchContextResolver.Result.Available) return
        val context = result.context
        val baseRef = context.configuredBaseRef
        if (baseRef != null) {
            settings.setConfiguration(context.rootUrl, context.branchName, baseRef, mode)
            return
        }

        chooseBase(context) selection@ { selected ->
            if (project.isDisposed) return@selection
            val current = contextResolver.resolve(file)
            if (current !is BranchContextResolver.Result.Available ||
                current.context.rootUrl != context.rootUrl ||
                current.context.branchName != context.branchName ||
                current.context.configuredBaseRef != null
            ) return@selection
            settings.setConfiguration(
                context.rootUrl,
                context.branchName,
                selected.fullName,
                mode,
            )
            invalidBases.remove(MappingKey(context.rootUrl, context.branchName))
        }
    }

    fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    fun toggleEnabled() = setEnabled(!settings.isEnabled())

    fun isEnabled(): Boolean = settings.isEnabled()

    fun currentFile(): VirtualFile? {
        if (project.isDisposed) return null
        if (!ApplicationManager.getApplication().isDispatchThread) return currentFileSnapshot
        return FileEditorManagerEx.getInstanceEx(project).currentFile.also { currentFileSnapshot = it }
    }

    fun hasResolvableBranchContext(file: VirtualFile? = currentFile()): Boolean =
        file != null && file.isValid && contextResolver.resolve(file) is BranchContextResolver.Result.Available

    fun activeBaseRef(file: VirtualFile? = currentFile()): String? {
        if (file == null || !file.isValid) return null
        return (contextResolver.resolve(file) as? BranchContextResolver.Result.Available)
            ?.context
            ?.configuredBaseRef
    }

    fun matchingSnapshot(file: VirtualFile? = currentFile()): ComparisonSnapshot? {
        if (file == null || !file.isValid ||
            !ApplicationManager.getApplication().isDispatchThread
        ) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val entry = documentEntries[document] ?: return null
        val snapshot = (entry.lastResult as? ComparisonResult.Available)?.snapshot ?: return null
        return snapshot.takeIf {
            validateSnapshot(entry, file, it, entry.validatedIndexIdentity) == SnapshotValidity.CURRENT
        }
    }

    fun activeComparisonMode(file: VirtualFile? = currentFile()): ComparisonMode {
        if (file == null || !file.isValid) return ComparisonMode.FORK_POINT
        val resolved = contextResolver.resolve(file)
        return (resolved as? BranchContextResolver.Result.Available)
            ?.context
            ?.comparisonMode
            ?: ComparisonMode.FORK_POINT
    }

    fun displayRef(ref: String): String = when {
        ref.startsWith("refs/remotes/") -> ref.removePrefix("refs/remotes/")
        ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
        else -> ref
    }

    fun refresh(cause: String) {
        if (project.isDisposed) return
        runOnEdt {
            if (project.isDisposed) return@runOnEdt
            validationGeneration.incrementAndGet()
            if (cause == "repository") baselinePathResolver.clear()
            currentFile()
            synchronizeEditors()
            if (!settings.isEnabled()) {
                cancelAndClearAll()
            } else {
                documentEntries.values.toList().forEach { schedule(it, cause) }
            }
            publishUiUpdate()
            val state = uiState(currentFileSnapshot)
            if (state is UiState.Base) validateConfiguredBase(state.context, state.baseRef)
        }
    }

    fun onEditorCreated(editor: Editor) = runOnEdt {
        if (editor.project === project && !editor.isDisposed) {
            val existing = documentEntries[editor.document]
            registerEditor(editor)
            val entry = documentEntries.getValue(editor.document)
            val snapshot = (existing?.lastResult as? ComparisonResult.Available)?.snapshot
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            if (snapshot == null || file == null) {
                schedule(entry, "editor-open")
            } else {
                when (validateSnapshot(entry, file, snapshot, null)) {
                    SnapshotValidity.CURRENT -> renderer.replace(editor, snapshot, ::showDiff)
                    SnapshotValidity.STALE -> {
                        clearCachedSnapshot(entry)
                        schedule(entry, "stale-editor-open")
                    }
                    SnapshotValidity.UNCERTAIN -> revalidateSnapshotIndex(
                        entry,
                        file,
                        snapshot,
                        onCurrent = {
                            if (!editor.isDisposed) renderer.replace(editor, snapshot, ::showDiff)
                        },
                        onStale = {
                            clearCachedSnapshot(entry)
                            schedule(entry, "stale-editor-open")
                        },
                    )
                }
            }
        }
        publishUiUpdate()
    }

    fun onEditorReleased(editor: Editor) = runOnEdt {
        unregisterEditor(editor)
        publishUiUpdate()
    }

    fun onEditorContextChanged() = refresh("editor")

    internal fun snapshotFor(document: Document): ComparisonSnapshot? =
        (documentEntries[document]?.lastResult as? ComparisonResult.Available)?.snapshot

    internal fun ownedHighlighters(editor: Editor) = renderer.ownedHighlighters(editor)

    fun showDiff(editor: Editor) = showDiff(editor, editor.caretModel.logicalPosition.line)

    fun showDiff(editor: Editor, targetLine: Int) = runOnEdt {
        if (editor.isDisposed || editor.project !== project) return@runOnEdt
        val entry = documentEntries[editor.document] ?: return@runOnEdt
        val snapshot = (entry.lastResult as? ComparisonResult.Available)?.snapshot
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (snapshot == null || file == null) {
            pendingDiffLines[editor] = targetLine
            schedule(entry, "stale-diff-click")
            return@runOnEdt
        }
        when (validateSnapshot(entry, file, snapshot, null)) {
            SnapshotValidity.CURRENT -> presentDiff(project, editor.document, file, snapshot, targetLine)
            SnapshotValidity.STALE -> {
                clearCachedSnapshot(entry)
                pendingDiffLines[editor] = targetLine
                schedule(entry, "stale-diff-click")
            }
            SnapshotValidity.UNCERTAIN -> revalidateSnapshotIndex(
                entry,
                file,
                snapshot,
                onCurrent = {
                    if (!editor.isDisposed) presentDiff(project, editor.document, file, snapshot, targetLine)
                },
                onStale = {
                    clearCachedSnapshot(entry)
                    pendingDiffLines[editor] = targetLine
                    schedule(entry, "stale-diff-click")
                },
            )
        }
    }

    internal fun beforePluginUnload(pluginId: String) {
        if (pluginId == PLUGIN_ID) runOnEdt(::cancelAndClearAll)
    }

    private fun synchronizeEditors() {
        EditorFactory.getInstance().allEditors
            .asSequence()
            .filter { it.project === project && !it.isDisposed }
            .forEach(::registerEditor)
        documentEntries.values.toList().forEach { entry ->
            entry.editors.filter(Editor::isDisposed).toList().forEach { entry.editors.remove(it) }
            if (entry.editors.isEmpty()) {
                entry.job?.cancel()
                entry.document.removeDocumentListener(entry.listener)
                documentEntries.remove(entry.document)
            }
        }
    }

    private fun registerEditor(editor: Editor) {
        if (documentEntries[editor.document]?.editors?.add(editor) == true) return
        if (documentEntries.containsKey(editor.document)) return
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                documentEntries[event.document]?.let { schedule(it, "document-change") }
            }
        }
        val editors = Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
        editors.add(editor)
        editor.document.addDocumentListener(listener)
        documentEntries[editor.document] = DocumentEntry(editor.document, editors, listener)
    }

    private fun unregisterEditor(editor: Editor) {
        renderer.clear(editor)
        pendingDiffLines.remove(editor)
        val entry = documentEntries[editor.document] ?: return
        entry.editors.remove(editor)
        if (entry.editors.isEmpty()) {
            entry.job?.cancel()
            entry.document.removeDocumentListener(entry.listener)
            documentEntries.remove(entry.document)
        }
    }

    private fun schedule(entry: DocumentEntry, cause: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        entry.job?.cancel()
        entry.generation++
        val generation = entry.generation
        val file = FileDocumentManager.getInstance().getFile(entry.document)
        if (!settings.isEnabled() || file == null || !file.isValid || file.fileType.isBinary) {
            clearEntry(entry, null)
            return
        }
        val context = when (val resolved = contextResolver.resolve(file)) {
            is BranchContextResolver.Result.Available -> resolved.context
            is BranchContextResolver.Result.Unavailable -> {
                clearEntry(entry, ComparisonResult.Unavailable(mapContextReason(resolved.reason)))
                return
            }
        }
        val baseRef = context.configuredBaseRef
        if (baseRef == null) {
            clearEntry(entry, ComparisonResult.Unavailable(ComparisonUnavailableReason.NO_BASE))
            return
        }
        if (invalidBases[MappingKey(context.rootUrl, context.branchName)] == baseRef) {
            clearEntry(entry, ComparisonResult.Unavailable(ComparisonUnavailableReason.INVALID_BASE))
            return
        }
        val configurationIdentity = baselineConfigurationIdentity(context, baseRef)
        if (entry.lastResult != null && entry.lastConfigurationIdentity != configurationIdentity) {
            clearCachedSnapshot(entry)
        }
        val cachedSnapshot = (entry.lastResult as? ComparisonResult.Available)?.snapshot
        if (cachedSnapshot != null && !baselineIdentityMatches(cachedSnapshot, context, baseRef)) {
            clearCachedSnapshot(entry)
        }
        val request = ComparisonRequest(
            entry.document,
            file,
            context,
            baseRef,
            context.comparisonMode,
            entry.document.immutableCharSequence.toString(),
            file.charset,
            entry.document.modificationStamp,
            generation,
        )
        val indicator = EmptyProgressIndicator()
        entry.job = coroutineScope.launch(Dispatchers.IO) {
            var timeoutJob: Job? = null
            try {
                delay(DEBOUNCE_MILLIS)
                timeoutJob = coroutineScope.launch(Dispatchers.IO) {
                    delay(COMPUTATION_TIMEOUT_MILLIS)
                    indicator.cancel()
                }
                val result = ProgressManager.getInstance().runProcess<ComparisonResult>(
                    { compute(request, indicator) },
                    indicator,
                )
                coroutineContext.ensureActive()
                applyResult(entry, request, result)
            } catch (_: CancellationException) {
                indicator.cancel()
            } catch (_: ProcessCanceledException) {
                if (coroutineContext.isActive) {
                    applyResult(entry, request, unavailable(ComparisonUnavailableReason.GIT_TIMEOUT))
                }
            } catch (error: Exception) {
                log.warn(
                    "Branch Changes comparison failed: root=${request.context.rootPath} path=${request.context.relativePath}",
                    error,
                )
                applyResult(entry, request, unavailable(ComparisonUnavailableReason.GIT_FAILURE))
            } finally {
                timeoutJob?.cancel()
            }
        }.also { job -> job.invokeOnCompletion { indicator.cancel() } }
        log.debug("Branch Changes refresh scheduled: cause=$cause generation=$generation path=${context.relativePath}")
    }

    private fun compute(request: ComparisonRequest, indicator: EmptyProgressIndicator): ComparisonResult {
        check(!ApplicationManager.getApplication().isDispatchThread) { "Git/diff work must not run on EDT" }
        val startedAt = System.nanoTime()
        val currentBytes = request.text.toByteArray(request.charset).size.toLong()
        if (currentBytes > ComparisonLimits.MAX_BYTES) return unavailable(ComparisonUnavailableReason.FILE_TOO_LARGE)
        val currentLines = normalizedLineCount(request.text)
        if (currentLines > ComparisonLimits.MAX_LINES) return unavailable(ComparisonUnavailableReason.TOO_MANY_LINES)

        val captured = when (val result = gitOperations.capture(request.context.repository, request.baseRef, request.mode)) {
            is GitOperationResult.Success -> result.value
            is GitOperationResult.Unavailable -> return ComparisonResult.Unavailable(result.reason, result.detail)
        }
        if (captured.headHash != request.context.headHash) return unavailable(ComparisonUnavailableReason.FILE_CHANGED)
        val baselinePath = when (val resolved = baselinePathResolver.resolve(
            request.context.repository,
            request.context.rootUrl,
            captured.baselineHash,
            captured.indexFingerprint,
            request.context.relativePath,
        )) {
            is BaselinePathResult.Success -> resolved.baselinePath
            is BaselinePathResult.Unavailable -> return ComparisonResult.Unavailable(resolved.reason, resolved.detail)
        }
        val baseline = when (val result = gitOperations.loadBaseline(
            request.context.repository,
            captured.baselineHash,
            baselinePath,
            request.charset,
        )) {
            is GitOperationResult.Success -> result.value
            is GitOperationResult.Unavailable -> return ComparisonResult.Unavailable(result.reason, result.detail)
        }
        val gitFinishedAt = System.nanoTime()
        val regions = when (val compared = lineComparator.compare(baseline.text, request.text, indicator)) {
            is LineComparisonResult.Success -> compared.regions
            is LineComparisonResult.Unavailable -> return unavailable(compared.reason)
        }
        val finalFingerprint = when (val result = gitOperations.indexFingerprint(request.context.repository)) {
            is GitOperationResult.Success -> result.value
            is GitOperationResult.Unavailable -> return ComparisonResult.Unavailable(result.reason, result.detail)
        }
        if (finalFingerprint != captured.indexFingerprint) return unavailable(ComparisonUnavailableReason.INDEX_CHANGED)

        val identity = ComparisonIdentity(
            request.context.rootUrl,
            request.context.relativePath,
            request.context.branchName,
            request.baseRef,
            request.mode,
            captured.baseTipHash,
            captured.headHash,
            captured.baselineHash,
            captured.indexFingerprint,
            request.documentStamp,
            request.generation,
        )
        val snapshot = ComparisonSnapshot(
                identity,
                baselinePath,
                request.context.relativePath,
                baseline.text,
                request.text,
                TextMetadata(
                    baseline.bytes.toLong(),
                    normalizedLineCount(baseline.text),
                    request.charset,
                    baseline.text.endsWith('\n'),
                ),
                TextMetadata(currentBytes, currentLines, request.charset, request.text.endsWith('\n')),
                regions,
            )
        val finishedAt = System.nanoTime()
        log.debug(
            "Branch Changes refresh complete: root=${request.context.rootPath} branch=${request.context.branchName} " +
                "base=${request.baseRef} mode=${request.mode.serializedId} baseline=${captured.baselineHash.take(7)} generation=${request.generation} " +
                "regions=${regions.size} gitMs=${(gitFinishedAt - startedAt) / 1_000_000} " +
                "diffMs=${(finishedAt - gitFinishedAt) / 1_000_000}",
        )
        return ComparisonResult.Available(snapshot)
    }

    private fun applyResult(entry: DocumentEntry, request: ComparisonRequest, result: ComparisonResult) {
        val applicationIndexIdentity: String?
        val applicableResult = if (result is ComparisonResult.Available) {
            when (val fingerprint = gitOperations.indexFingerprint(request.context.repository)) {
                is GitOperationResult.Success -> {
                    applicationIndexIdentity = fingerprint.value
                    if (fingerprint.value == result.snapshot.identity.indexFingerprint) {
                        result
                    } else {
                        unavailable(ComparisonUnavailableReason.INDEX_CHANGED)
                    }
                }
                is GitOperationResult.Unavailable -> {
                    applicationIndexIdentity = null
                    ComparisonResult.Unavailable(fingerprint.reason, fingerprint.detail)
                }
            }
        } else {
            applicationIndexIdentity = null
            result
        }

        runOnEdt {
            if (project.isDisposed || documentEntries[request.document] !== entry ||
                entry.generation != request.generation || request.document.modificationStamp != request.documentStamp ||
                !request.file.isValid || FileDocumentManager.getInstance().getFile(request.document) != request.file
            ) return@runOnEdt
            val current = contextResolver.resolve(request.file)
            if (current !is BranchContextResolver.Result.Available ||
                current.context.rootUrl != request.context.rootUrl ||
                current.context.relativePath != request.context.relativePath ||
                current.context.branchName != request.context.branchName ||
                current.context.headHash != request.context.headHash ||
                current.context.configuredBaseRef != request.baseRef ||
                current.context.comparisonMode != request.mode
            ) return@runOnEdt
            if (applicableResult is ComparisonResult.Available &&
                validateSnapshot(
                    entry,
                    request.file,
                    applicableResult.snapshot,
                    applicationIndexIdentity,
                ) != SnapshotValidity.CURRENT
            ) {
                clearCachedSnapshot(entry)
                schedule(entry, "stale-result")
                return@runOnEdt
            }

            entry.lastResult = applicableResult
            entry.lastConfigurationIdentity = baselineConfigurationIdentity(current.context, request.baseRef)
            entry.validatedIndexIdentity = applicationIndexIdentity
            when (applicableResult) {
                is ComparisonResult.Available -> entry.editors.toList().forEach { editor ->
                    if (!editor.isDisposed) {
                        renderer.replace(editor, applicableResult.snapshot, ::showDiff)
                        val pendingLine = pendingDiffLines.remove(editor)
                        if (pendingLine != null &&
                            validateSnapshot(
                                entry,
                                request.file,
                                applicableResult.snapshot,
                                applicationIndexIdentity,
                            ) == SnapshotValidity.CURRENT
                        ) {
                            presentDiff(project, editor.document, request.file, applicableResult.snapshot, pendingLine)
                        }
                    }
                }
                is ComparisonResult.Unavailable -> {
                    warnOnce(request, applicableResult)
                    entry.editors.toList().forEach(renderer::clear)
                    entry.editors.forEach(pendingDiffLines::remove)
                }
            }
            publishUiUpdate()
        }
    }

    private fun revalidateSnapshotIndex(
        entry: DocumentEntry,
        file: VirtualFile,
        snapshot: ComparisonSnapshot,
        onCurrent: () -> Unit,
        onStale: () -> Unit,
    ) {
        val resolved = contextResolver.resolve(file)
        if (resolved !is BranchContextResolver.Result.Available) {
            onStale()
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            val fingerprint = (gitOperations.indexFingerprint(resolved.context.repository) as? GitOperationResult.Success)
                ?.value
            coroutineContext.ensureActive()
            runOnEdt {
                if (fingerprint != null &&
                    validateSnapshot(entry, file, snapshot, fingerprint) == SnapshotValidity.CURRENT
                ) {
                    entry.validatedIndexIdentity = fingerprint
                    onCurrent()
                } else {
                    onStale()
                }
            }
        }
    }

    private fun validateSnapshot(
        entry: DocumentEntry,
        file: VirtualFile,
        snapshot: ComparisonSnapshot,
        availableIndexIdentity: String?,
    ): SnapshotValidity {
        if (project.isDisposed || !settings.isEnabled() || !file.isValid ||
            documentEntries[entry.document] !== entry ||
            FileDocumentManager.getInstance().getFile(entry.document) != file
        ) return SnapshotValidity.STALE

        val identity = snapshot.identity
        if (entry.generation != identity.generation ||
            entry.document.modificationStamp != identity.documentModificationStamp
        ) return SnapshotValidity.STALE

        val current = contextResolver.resolve(file)
        if (current !is BranchContextResolver.Result.Available) return SnapshotValidity.STALE
        val context = current.context
        if (context.rootUrl != identity.rootUrl ||
            context.relativePath != identity.relativePath ||
            context.branchName != identity.branchName ||
            context.configuredBaseRef != identity.baseRef ||
            context.comparisonMode != identity.mode ||
            context.headHash != identity.headHash ||
            currentBaseTip(context, identity.baseRef) != identity.baseTipHash
        ) return SnapshotValidity.STALE

        return when {
            availableIndexIdentity == null -> SnapshotValidity.UNCERTAIN
            availableIndexIdentity != identity.indexFingerprint -> SnapshotValidity.STALE
            else -> SnapshotValidity.CURRENT
        }
    }

    private fun baselineConfigurationIdentity(
        context: BranchContext,
        baseRef: String,
    ) = BaselineConfigurationIdentity(
        context.rootUrl,
        context.relativePath,
        context.branchName,
        baseRef,
        context.comparisonMode,
        context.headHash,
        currentBaseTip(context, baseRef),
    )

    private fun baselineIdentityMatches(
        snapshot: ComparisonSnapshot,
        context: BranchContext,
        baseRef: String,
    ): Boolean {
        val identity = snapshot.identity
        return context.rootUrl == identity.rootUrl &&
            context.relativePath == identity.relativePath &&
            context.branchName == identity.branchName &&
            baseRef == identity.baseRef &&
            context.comparisonMode == identity.mode &&
            context.headHash == identity.headHash &&
            currentBaseTip(context, baseRef) == identity.baseTipHash
    }

    private fun currentBaseTip(context: BranchContext, baseRef: String): String? =
        context.repository.info.localBranchesWithHashes.entries
            .asSequence()
            .map { it.key.fullName to it.value.asString() }
            .plus(
                context.repository.info.remoteBranchesWithHashes.entries
                    .asSequence()
                    .map { it.key.fullName to it.value.asString() },
            )
            .firstOrNull { it.first == baseRef }
            ?.second

    private fun warnOnce(request: ComparisonRequest, result: ComparisonResult.Unavailable) {
        if (result.reason !in WARN_REASONS) return
        val key = "${request.context.rootUrl}|${request.context.relativePath}|${result.reason}|${result.detail}"
        if (warnedFailures.add(key)) {
            log.warn(
                "Branch Changes comparison unavailable: root=${request.context.rootPath} " +
                    "path=${request.context.relativePath} reason=${result.reason}" +
                    result.detail?.let { " detail=${it.take(240)}" }.orEmpty(),
            )
        }
    }

    private fun clearCachedSnapshot(entry: DocumentEntry) {
        entry.lastResult = null
        entry.lastConfigurationIdentity = null
        entry.validatedIndexIdentity = null
        entry.editors.toList().forEach(renderer::clear)
    }

    private fun clearEntry(entry: DocumentEntry, result: ComparisonResult?) {
        entry.job?.cancel()
        entry.job = null
        entry.lastResult = result
        entry.lastConfigurationIdentity = null
        entry.validatedIndexIdentity = null
        entry.editors.toList().forEach(renderer::clear)
    }

    private fun cancelAndClearAll() {
        documentEntries.values.forEach { clearEntry(it, unavailable(ComparisonUnavailableReason.DISABLED)) }
    }

    private fun validateConfiguredBase(context: BranchContext, baseRef: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val validation = refProvider.validate(context.repository, context.branchName, baseRef)
            coroutineContext.ensureActive()
            if (project.isDisposed || settings.getBase(context.rootUrl, context.branchName) != baseRef) return@launch
            val key = MappingKey(context.rootUrl, context.branchName)
            val changed = when (validation) {
                is BranchRefProvider.Validation.Valid -> invalidBases.remove(key) != null
                BranchRefProvider.Validation.Cancelled -> return@launch
                BranchRefProvider.Validation.CurrentBranch,
                BranchRefProvider.Validation.Invalid,
                -> invalidBases.put(key, baseRef) != baseRef
            }
            if (changed) refresh("base-validation") else publishUiUpdate()
        }
    }

    private fun publishUiUpdate() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            project.messageBus.syncPublisher(UI_TOPIC).stateChanged()
            EditorNotifications.getInstance(project).updateAllNotifications()
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(WIDGET_ID)
        }, ModalityState.nonModal())
    }

    private fun mapContextReason(reason: BranchContextResolver.UnavailableReason) = when (reason) {
        BranchContextResolver.UnavailableReason.NOT_GIT -> ComparisonUnavailableReason.NOT_GIT
        BranchContextResolver.UnavailableReason.DETACHED_HEAD -> ComparisonUnavailableReason.DETACHED_HEAD
        BranchContextResolver.UnavailableReason.UNBORN_BRANCH -> ComparisonUnavailableReason.UNBORN_BRANCH
        BranchContextResolver.UnavailableReason.NO_LOCAL_BRANCH -> ComparisonUnavailableReason.NO_LOCAL_BRANCH
    }

    private fun unavailable(reason: ComparisonUnavailableReason) = ComparisonResult.Unavailable(reason)

    private fun runOnEdt(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) action()
        else ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal())
    }

    override fun dispose() {
        disposed = true
        validationGeneration.incrementAndGet()
        currentFileSnapshot = null
        invalidBases.clear()
        warnedFailures.clear()
        pendingDiffLines.clear()
        documentEntries.values.forEach { entry ->
            entry.job?.cancel()
            entry.document.removeDocumentListener(entry.listener)
        }
        documentEntries.clear()
        if (ApplicationManager.getApplication().isDispatchThread) renderer.clearAll()
        else ApplicationManager.getApplication().invokeLater(renderer::clearAll)
    }

    companion object {
        const val WIDGET_ID = "BranchChanges"
        private const val PLUGIN_ID = "com.github.flobilosaurus.branchchanges"
        private const val DEBOUNCE_MILLIS = 250L
        private const val COMPUTATION_TIMEOUT_MILLIS = 15_000L
        private val WARN_REASONS = setOf(
            ComparisonUnavailableReason.GIT_FAILURE,
            ComparisonUnavailableReason.MALFORMED_GIT_OUTPUT,
            ComparisonUnavailableReason.MISSING_OBJECT,
            ComparisonUnavailableReason.GIT_TIMEOUT,
            ComparisonUnavailableReason.NO_MERGE_BASE,
            ComparisonUnavailableReason.MULTIPLE_MERGE_BASES,
        )
        private val TRANSIENT_UI_REASONS = setOf(
            ComparisonUnavailableReason.CANCELLED,
            ComparisonUnavailableReason.DISABLED,
            ComparisonUnavailableReason.NO_BASE,
        )

        @JvmField
        val UI_TOPIC: Topic<UiListener> = Topic.create("Branch Changes UI state", UiListener::class.java)

        @JvmStatic
        fun getInstance(project: Project): BranchChangesProjectService =
            project.getService(BranchChangesProjectService::class.java)
    }
}
