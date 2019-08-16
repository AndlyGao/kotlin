/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.scratch.ui


import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.ui.ConfigurationModuleSelector
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.committed.LabeledComboBoxAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.caches.project.productionSourceInfo
import org.jetbrains.kotlin.idea.caches.project.testSourceInfo
import org.jetbrains.kotlin.idea.scratch.*
import org.jetbrains.kotlin.idea.scratch.actions.ClearScratchAction
import org.jetbrains.kotlin.idea.scratch.actions.RunScratchAction
import org.jetbrains.kotlin.idea.scratch.actions.StopScratchAction
import org.jetbrains.kotlin.idea.scratch.output.ScratchOutputHandlerAdapter
import javax.swing.JComponent

class ScratchTopPanel private constructor(val scratchFile: ScratchFile) : Disposable {
    override fun dispose() {
        scratchFile.replScratchExecutor?.stop()
        scratchFile.compilingScratchExecutor?.stop()
        scratchFile.editor.removeScratchPanel()
    }

    companion object {
        fun createPanel(project: Project, virtualFile: VirtualFile, editor: TextEditor) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
            val scratchFile = ScratchFileLanguageProvider.get(psiFile.language)?.newScratchFile(project, editor) ?: return
            val panel = ScratchTopPanel(scratchFile)

            val toolbarHandler = createUpdateToolbarHandler(panel)
            scratchFile.replScratchExecutor?.addOutputHandler(object : ScratchOutputHandlerAdapter() {
                override fun onFinish(file: ScratchFile) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!file.project.isDisposed) {
                            val scratch = file.getPsiFile()
                            if (scratch?.isValid == true) {
                                DaemonCodeAnalyzer.getInstance(project).restart(scratch)
                            }
                        }
                    }
                }
            })
            scratchFile.replScratchExecutor?.addOutputHandler(toolbarHandler)
            scratchFile.compilingScratchExecutor?.addOutputHandler(toolbarHandler)

            editor.addScratchPanel(panel)
        }

        private fun createUpdateToolbarHandler(panel: ScratchTopPanel) = object : ScratchOutputHandlerAdapter() {
            override fun onStart(file: ScratchFile) {
                panel.updateToolbar()
            }

            override fun onFinish(file: ScratchFile) {
                panel.updateToolbar()
            }
        }
    }

    private val moduleChooserAction: ModulesComboBoxAction = ModulesComboBoxAction()
    private val actionsToolbar: ActionToolbar

    init {
        scratchFile.addModuleListener { _, _ -> updateToolbar() }

        val toolbarGroup = DefaultActionGroup().apply {
            add(RunScratchAction())
            add(StopScratchAction())
            addSeparator()
            add(ClearScratchAction())
            addSeparator()
            add(moduleChooserAction)
            add(IsMakeBeforeRunAction())
            add(IsInteractiveCheckboxAction())
            add(IsReplCheckboxAction())
        }

        actionsToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, toolbarGroup, true)
    }

    val component: JComponent = actionsToolbar.component

    @TestOnly
    fun setReplMode(isSelected: Boolean) {
        scratchFile.saveOptions { copy(isRepl = isSelected) }
    }

    @TestOnly
    fun setMakeBeforeRun(isSelected: Boolean) {
        scratchFile.saveOptions { copy(isMakeBeforeRun = isSelected) }
    }

    @TestOnly
    fun setInteractiveMode(isSelected: Boolean) {
        scratchFile.saveOptions { copy(isInteractiveMode = isSelected) }
    }

    @TestOnly
    fun getModuleSelectorAction(): AnAction = moduleChooserAction

    private fun updateToolbar() {
        ApplicationManager.getApplication().invokeLater {
            actionsToolbar.updateActionsImmediately()
        }
    }

    private inner class IsMakeBeforeRunAction : CheckboxAction("Make module before Run") {
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isVisible = scratchFile.module != null
        }

        override fun isSelected(e: AnActionEvent): Boolean {
            return scratchFile.options.isMakeBeforeRun
        }

        override fun setSelected(e: AnActionEvent, isMakeBeforeRun: Boolean) {
            scratchFile.saveOptions { copy(isMakeBeforeRun = isMakeBeforeRun) }
        }
    }

    private inner class IsInteractiveCheckboxAction : CheckboxAction("Interactive mode") {
        override fun isSelected(e: AnActionEvent): Boolean {
            return scratchFile.options.isInteractiveMode
        }

        override fun setSelected(e: AnActionEvent, isInteractiveMode: Boolean) {
            scratchFile.saveOptions { copy(isInteractiveMode = isInteractiveMode) }
        }
    }

    private inner class IsReplCheckboxAction : CheckboxAction("Use REPL") {
        override fun isSelected(e: AnActionEvent): Boolean {
            return scratchFile.options.isRepl
        }

        override fun setSelected(e: AnActionEvent, isRepl: Boolean) {
            scratchFile.saveOptions { copy(isRepl = isRepl) }

            if (isRepl) {
                // TODO start REPL process when checkbox is selected to speed up execution
                // Now it is switched off due to KT-18355: REPL process is keep alive if no command is executed
                //scratchFile.replScratchExecutor?.start()
            } else {
                scratchFile.replScratchExecutor?.stop()
            }
        }
    }

    private inner class ModulesComboBoxAction : LabeledComboBoxAction("Use classpath of module") {
        override fun createPopupActionGroup(button: JComponent?): DefaultActionGroup =
            throw UnsupportedOperationException("Should not be called!")

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val actionGroup = DefaultActionGroup()
            actionGroup.add(ModuleIsNotSelectedAction(ConfigurationModuleSelector.NO_MODULE_TEXT))

            val modules = ModuleManager.getInstance(scratchFile.project).modules.filter {
                it.productionSourceInfo() != null || it.testSourceInfo() != null
            }

            actionGroup.addAll(modules.map { SelectModuleAction(it) })

            return actionGroup
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            val selectedModule = scratchFile.module

            e.presentation.apply {
                icon = selectedModule?.let { ModuleType.get(it).icon }
                text = selectedModule?.name ?: ConfigurationModuleSelector.NO_MODULE_TEXT
            }

            val isWorksheetFile = scratchFile.getPsiFile()?.virtualFile?.isKotlinWorksheet == true
            e.presentation.isVisible = !isWorksheetFile
        }

        private inner class ModuleIsNotSelectedAction(placeholder: String) : DumbAwareAction(placeholder) {
            override fun actionPerformed(e: AnActionEvent) {
                scratchFile.setModule(null)
            }
        }

        private inner class SelectModuleAction(private val module: Module) :
            DumbAwareAction(module.name, null, ModuleType.get(module).icon) {
            override fun actionPerformed(e: AnActionEvent) {
                scratchFile.setModule(module)
            }
        }
    }
}

interface ScratchPanelListener {
    fun panelAdded(panel: ScratchTopPanel)

    companion object {
        val TOPIC = Topic.create("ScratchPanelListener", ScratchPanelListener::class.java)
    }
}
