package com.github.flobilosaurus.branchchanges.settings

import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.messages.Topic

@Service(Service.Level.PROJECT)
@State(
    name = "BranchChangesSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class BranchBaseSettings(private val project: Project) : PersistentStateComponent<BranchBaseSettings.SettingsState> {
    class SettingsState {
        var enabled: Boolean = true
        var mappings: MutableList<BaseMappingState> = mutableListOf()
    }

    class BaseMappingState {
        var rootUrl: String = ""
        var branchName: String = ""
        var baseRef: String = ""
        var mode: String? = null

        constructor()

        constructor(rootUrl: String, branchName: String, baseRef: String) : this(
            rootUrl,
            branchName,
            baseRef,
            null,
        )

        constructor(rootUrl: String, branchName: String, baseRef: String, mode: String?) {
            this.rootUrl = rootUrl
            this.branchName = branchName
            this.baseRef = baseRef
            this.mode = mode
        }
    }

    data class Configuration(
        val baseRef: String,
        val mode: ComparisonMode,
    )

    private var settingsState = SettingsState()

    @Synchronized
    override fun getState(): SettingsState = SettingsState().also { snapshot ->
        snapshot.enabled = settingsState.enabled
        snapshot.mappings = settingsState.mappings.map {
            BaseMappingState(it.rootUrl, it.branchName, it.baseRef, it.mode)
        }.toMutableList()
    }

    @Synchronized
    override fun loadState(state: SettingsState) {
        settingsState = SettingsState().also { loaded ->
            loaded.enabled = state.enabled
            loaded.mappings = state.mappings
                .asSequence()
                .filter { it.rootUrl.isNotBlank() && it.branchName.isNotBlank() && it.baseRef.isNotBlank() }
                .map {
                    BaseMappingState(
                        normalizeRootUrl(it.rootUrl),
                        it.branchName,
                        it.baseRef,
                        ComparisonMode.fromSerialized(it.mode).serializedId,
                    )
                }
                .distinctBy { it.rootUrl to it.branchName }
                .toMutableList()
        }
    }

    @Synchronized
    fun isEnabled(): Boolean = settingsState.enabled

    fun setEnabled(enabled: Boolean) {
        val changed = synchronized(this) {
            if (settingsState.enabled == enabled) {
                false
            } else {
                settingsState.enabled = enabled
                true
            }
        }
        if (changed) notifyChanged()
    }

    @Synchronized
    fun getConfiguration(rootUrl: String, branchName: String): Configuration? {
        val normalizedRoot = normalizeRootUrl(rootUrl)
        return settingsState.mappings
            .firstOrNull { it.rootUrl == normalizedRoot && it.branchName == branchName }
            ?.let { Configuration(it.baseRef, ComparisonMode.fromSerialized(it.mode)) }
    }

    fun getBase(rootUrl: String, branchName: String): String? =
        getConfiguration(rootUrl, branchName)?.baseRef

    fun getMode(rootUrl: String, branchName: String): ComparisonMode =
        getConfiguration(rootUrl, branchName)?.mode ?: ComparisonMode.FORK_POINT

    fun setBase(rootUrl: String, branchName: String, baseRef: String) {
        val mode = getConfiguration(rootUrl, branchName)?.mode ?: ComparisonMode.FORK_POINT
        setConfiguration(rootUrl, branchName, baseRef, mode)
    }

    fun setConfiguration(rootUrl: String, branchName: String, baseRef: String, mode: ComparisonMode) {
        require(branchName.isNotBlank()) { "Branch name must not be blank" }
        require(baseRef.isNotBlank()) { "Base ref must not be blank" }

        val normalizedRoot = normalizeRootUrl(rootUrl)
        val changed = synchronized(this) {
            val existing = settingsState.mappings
                .firstOrNull { it.rootUrl == normalizedRoot && it.branchName == branchName }
            when {
                existing == null -> {
                    settingsState.mappings.add(
                        BaseMappingState(normalizedRoot, branchName, baseRef, mode.serializedId),
                    )
                    true
                }

                existing.baseRef != baseRef || ComparisonMode.fromSerialized(existing.mode) != mode -> {
                    existing.baseRef = baseRef
                    existing.mode = mode.serializedId
                    true
                }

                else -> false
            }
        }
        if (changed) notifyChanged()
    }

    fun removeBase(rootUrl: String, branchName: String) {
        val normalizedRoot = normalizeRootUrl(rootUrl)
        val changed = synchronized(this) {
            settingsState.mappings.removeIf {
                it.rootUrl == normalizedRoot && it.branchName == branchName
            }
        }
        if (changed) notifyChanged()
    }

    @Synchronized
    fun mappings(): List<BaseMappingState> = settingsState.mappings.map {
        BaseMappingState(it.rootUrl, it.branchName, it.baseRef, it.mode)
    }

    private fun notifyChanged() {
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(TOPIC).settingsChanged()
        }
    }

    fun interface Listener {
        fun settingsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Branch Changes settings", Listener::class.java)

        @JvmStatic
        fun getInstance(project: Project): BranchBaseSettings = project.getService(BranchBaseSettings::class.java)

        @JvmStatic
        fun normalizeRootUrl(rootUrl: String): String {
            val normalized = FileUtil.toSystemIndependentName(rootUrl.trim())
            val schemeSeparator = normalized.indexOf("://")
            if (schemeSeparator >= 0) {
                val prefix = normalized.substring(0, schemeSeparator + 3)
                val path = normalized.substring(schemeSeparator + 3)
                val canonicalPath = FileUtil.toCanonicalPath("/$path").trimEnd('/')
                return prefix + canonicalPath.ifEmpty { "/" }
            }

            val canonical = FileUtil.toCanonicalPath(normalized).trimEnd('/')
            return canonical.ifEmpty { "/" }
        }
    }
}
