package com.github.flobilosaurus.branchchanges

import com.github.flobilosaurus.branchchanges.git.BranchContextResolver
import com.github.flobilosaurus.branchchanges.git.BranchRefProvider
import com.github.flobilosaurus.branchchanges.git.GitBranchRefProvider
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.github.flobilosaurus.branchchanges.ui.BranchBaseChooser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class BranchChangesProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    sealed interface UiState {
        data object Disabled : UiState
        data object NoFile : UiState
        data class SelectBase(val context: BranchContextResolver.Context) : UiState
        data class Base(val context: BranchContextResolver.Context, val baseRef: String) : UiState
        data class Unavailable(
            val reason: BranchContextResolver.UnavailableReason,
        ) : UiState

        data class InvalidBase(
            val context: BranchContextResolver.Context,
            val baseRef: String,
        ) : UiState
    }

    fun interface UiListener {
        fun stateChanged()
    }

    private data class MappingKey(val rootUrl: String, val branchName: String)

    private val settings = BranchBaseSettings.getInstance(project)
    private val contextResolver = BranchContextResolver(project, settings)
    private val refProvider: BranchRefProvider = GitBranchRefProvider(project)
    private val chooser = BranchBaseChooser(project, refProvider, coroutineScope)
    private val initialized = AtomicBoolean(false)
    private val validationGeneration = AtomicLong()
    private val invalidBases = ConcurrentHashMap<MappingKey, String>()

    @Volatile
    private var currentFileSnapshot: VirtualFile? = null

    @Volatile
    internal var disposed: Boolean = false
        private set

    fun initialize() {
        if (!initialized.compareAndSet(false, true) || project.isDisposed) return

        project.messageBus.connect(this).apply {
            subscribe(BranchBaseSettings.TOPIC, BranchBaseSettings.Listener {
                refresh("settings")
            })
            subscribe(GitRepository.GIT_REPO_CHANGE, git4idea.repo.GitRepositoryChangeListener {
                refresh("repository")
            })
        }
        refresh("startup")
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
                if (invalidBases[key] == base) {
                    UiState.InvalidBase(context, base)
                } else {
                    UiState.Base(context, base)
                }
            }
        }
    }

    fun selectBase(file: VirtualFile? = currentFile()) {
        if (project.isDisposed || file == null) return
        val result = contextResolver.resolve(file)
        if (result !is BranchContextResolver.Result.Available) return
        val context = result.context

        chooser.choose(context) { selected ->
            if (project.isDisposed) return@choose
            val current = contextResolver.resolve(file)
            if (current !is BranchContextResolver.Result.Available ||
                current.context.rootUrl != context.rootUrl ||
                current.context.branchName != context.branchName
            ) {
                return@choose
            }
            val previous = settings.getBase(context.rootUrl, context.branchName)
            settings.setBase(context.rootUrl, context.branchName, selected.fullName)
            invalidBases.remove(MappingKey(context.rootUrl, context.branchName))
            if (previous == selected.fullName) refresh("base-reselected")
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
    }

    fun toggleEnabled() = setEnabled(!settings.isEnabled())

    fun isEnabled(): Boolean = settings.isEnabled()

    fun currentFile(): VirtualFile? {
        if (project.isDisposed) return null
        if (!ApplicationManager.getApplication().isDispatchThread) return currentFileSnapshot

        return FileEditorManagerEx.getInstanceEx(project).currentFile.also {
            currentFileSnapshot = it
        }
    }

    fun displayRef(ref: String): String = when {
        ref.startsWith("refs/remotes/") -> ref.removePrefix("refs/remotes/")
        ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
        else -> ref
    }

    fun refresh(cause: String) {
        if (project.isDisposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({
                refresh(cause)
            }, ModalityState.nonModal())
            return
        }

        val generation = validationGeneration.incrementAndGet()
        val file = currentFile()
        publishUiUpdate()

        val state = uiState(file)
        if (state is UiState.Base) {
            validateConfiguredBase(state.context, state.baseRef, generation)
        }
    }

    fun onEditorContextChanged() = refresh("editor")

    private fun validateConfiguredBase(
        context: BranchContextResolver.Context,
        baseRef: String,
        generation: Long,
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val validation = refProvider.validate(context.repository, context.branchName, baseRef)
            coroutineContext.ensureActive()
            if (project.isDisposed || validationGeneration.get() != generation) return@launch
            val key = MappingKey(context.rootUrl, context.branchName)
            when (validation) {
                is BranchRefProvider.Validation.Valid -> invalidBases.remove(key)
                BranchRefProvider.Validation.Cancelled -> return@launch
                BranchRefProvider.Validation.CurrentBranch,
                BranchRefProvider.Validation.Invalid,
                -> invalidBases[key] = baseRef
            }
            publishUiUpdate()
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

    override fun dispose() {
        disposed = true
        validationGeneration.incrementAndGet()
        currentFileSnapshot = null
        invalidBases.clear()
    }

    companion object {
        const val WIDGET_ID = "BranchChanges"

        @JvmField
        val UI_TOPIC: Topic<UiListener> = Topic.create("Branch Changes UI state", UiListener::class.java)

        @JvmStatic
        fun getInstance(project: Project): BranchChangesProjectService =
            project.getService(BranchChangesProjectService::class.java)
    }
}
