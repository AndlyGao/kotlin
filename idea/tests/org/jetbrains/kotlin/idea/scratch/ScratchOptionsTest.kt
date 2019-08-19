/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scratch

import org.jetbrains.kotlin.idea.scratch.ui.ModulesComboBoxAction
import org.jetbrains.kotlin.test.JUnit3WithIdeaConfigurationRunner
import org.junit.Assert
import org.junit.runner.RunWith

@RunWith(JUnit3WithIdeaConfigurationRunner::class)
class ScratchOptionsTest : AbstractScratchRunActionTest() {

    fun testOptionsSaveOnClosingFile() {
        val scratchFileBeforeClosingFile = configureScratchByText("scratch_1.kts", testScratchText())

        val newIsReplValue = !scratchFileBeforeClosingFile.options.isRepl
        val newIsMakeBeforeRunValue = !scratchFileBeforeClosingFile.options.isMakeBeforeRun
        val newIsInteractiveModeValue = !scratchFileBeforeClosingFile.options.isInteractiveMode

        scratchFileBeforeClosingFile.saveOptions {
            copy(
                isRepl = newIsReplValue,
                isMakeBeforeRun = newIsMakeBeforeRunValue,
                isInteractiveMode = newIsInteractiveModeValue
            )
        }

        myManager.closeFile(myFixture.file.virtualFile)
        myManager.openFile(myFixture.file.virtualFile, true)

        val scratchFileAfterClosingFile = getScratchEditorForSelectedFile(myManager, myFixture.file.virtualFile)?.scratchFile
            ?: error("Couldn't find scratch panel")

        Assert.assertEquals("Wrong value for isRepl checkbox", newIsReplValue, scratchFileAfterClosingFile.options.isRepl)
        Assert.assertEquals(
            "Wrong value for isMakeBeforeRun checkbox",
            newIsMakeBeforeRunValue,
            scratchFileAfterClosingFile.options.isMakeBeforeRun
        )
        Assert.assertEquals(
            "Wrong value for isInteractiveMode checkbox",
            newIsInteractiveModeValue,
            scratchFileAfterClosingFile.options.isInteractiveMode
        )
    }

    fun testModuleSelectionPanelIsVisibleForScratchFile() {
        val scratchFile = configureScratchByText("scratch_1.kts", testScratchText())

        Assert.assertTrue("Module selector should be visible for scratches", isModuleSelectorVisible(scratchFile))
    }

    fun testModuleSelectionPanelIsHiddenForWorksheetFile() {
        val scratchFile = configureWorksheetByText("worksheet.ws.kts", testScratchText())

        Assert.assertFalse("Module selector should be hidden for worksheets", isModuleSelectorVisible(scratchFile))
    }

    fun testCurrentModuleIsAutomaticallySelectedForWorksheetFile() {
        val scratchFile = configureWorksheetByText("worksheet.ws.kts", testScratchText())

        Assert.assertEquals(
            "Selected module should be equal to current project module for worksheets",
            myFixture.module,
            scratchFile.module
        )
    }

    private fun isModuleSelectorVisible(scratchTopPanel: ScratchFile): Boolean {
        return ModulesComboBoxAction(scratchTopPanel).isModuleSelectorVisible()
    }

}