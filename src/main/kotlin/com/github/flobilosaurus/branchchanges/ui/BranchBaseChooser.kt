package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.git.BranchRefProvider
import com.github.flobilosaurus.branchchanges.model.BranchContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import java.awt.BorderLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class BranchBaseChooser(
    private val project: Project,
    private val refProvider: BranchRefProvider,
    private val coroutineScope: CoroutineScope,
) {
    private val log = Logger.getInstance(BranchBaseChooser::class.java)
    fun choose(
        context: BranchContext,
        onSelected: (BranchRefProvider.Validation.Valid) -> Unit,
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val discovery = try {
                refProvider.discover(context.repository)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.warn("Branch Changes base discovery failed", error)
                BranchRefProvider.Discovery(emptyList(), null)
            }
            coroutineContext.ensureActive()
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                val dialog = BaseDialog(context, discovery)
                if (dialog.showAndGet()) {
                    dialog.selection?.let(onSelected)
                }
            }, ModalityState.nonModal())
        }
    }

    private inner class BaseDialog(
        private val context: BranchContext,
        discovery: BranchRefProvider.Discovery,
    ) : DialogWrapper(project, true) {
        private val model = DefaultListModel<BranchRefProvider.Ref>()
        private val list = JBList(model)
        private var validating = false

        var selection: BranchRefProvider.Validation.Valid? = null
            private set

        init {
            title = BranchChangesBundle.message("chooser.title")
            discovery.refs.forEach(model::addElement)
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list.cellRenderer = SimpleListCellRenderer.create("") { ref ->
                ref.displayName + if (ref == discovery.suggestedRef) {
                    BranchChangesBundle.message("chooser.defaultSuffix")
                } else {
                    ""
                }
            }
            ListSpeedSearch.installOn(list) { it.displayName }

            val initial = context.configuredBaseRef?.let { configured ->
                discovery.refs.firstOrNull { it.fullName == configured || it.displayName == configured }
            } ?: discovery.suggestedRef ?: discovery.refs.firstOrNull()
            initial?.let { list.setSelectedValue(it, true) }

            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount == 2 && list.selectedValue != null) doOKAction()
                }
            })
            init()
            setOKActionEnabled(!model.isEmpty)
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, 8))
            panel.add(
                JBLabel(BranchChangesBundle.message("chooser.prompt", context.branchName)),
                BorderLayout.NORTH,
            )
            panel.add(
                if (model.isEmpty) {
                    JBLabel(BranchChangesBundle.message("chooser.empty"))
                } else {
                    ScrollPaneFactory.createScrollPane(list, true)
                },
                BorderLayout.CENTER,
            )
            panel.preferredSize = java.awt.Dimension(440, 300)
            return panel
        }

        override fun getPreferredFocusedComponent(): JComponent = list

        override fun doOKAction() {
            if (validating) return
            val selected = list.selectedValue ?: return
            validating = true
            setOKActionEnabled(false)
            setErrorText(null)

            coroutineScope.launch(Dispatchers.IO) {
                val validation = try {
                    refProvider.validate(
                        context.repository,
                        context.branchName,
                        selected.fullName,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.warn("Branch Changes base validation failed", error)
                    BranchRefProvider.Validation.Invalid
                }
                coroutineContext.ensureActive()
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed || project.isDisposed) return@invokeLater
                    validating = false
                    when (validation) {
                        is BranchRefProvider.Validation.Valid -> {
                            selection = validation
                            close(OK_EXIT_CODE)
                        }

                        BranchRefProvider.Validation.CurrentBranch -> {
                            setErrorText(
                                BranchChangesBundle.message("chooser.current", context.branchName),
                                list,
                            )
                            setOKActionEnabled(true)
                        }

                        BranchRefProvider.Validation.Invalid -> {
                            setErrorText(
                                BranchChangesBundle.message("chooser.invalid", selected.displayName),
                                list,
                            )
                            setOKActionEnabled(true)
                        }

                        BranchRefProvider.Validation.Cancelled -> setOKActionEnabled(true)
                    }
                }, ModalityState.stateForComponent(list))
            }
        }
    }
}
