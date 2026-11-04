package com.crosspaste.workbench

import com.crosspaste.i18n.GlobalCopywriter
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteType
import com.crosspaste.paste.item.PasteFiles
import com.crosspaste.paste.item.PasteItemReader
import com.crosspaste.paste.item.getFilePaths
import com.crosspaste.paste.menu.PasteMenuAction
import com.crosspaste.paste.menu.PasteMenuContributor
import com.crosspaste.path.UserDataPathProvider
import com.crosspaste.utils.GlobalCoroutineScope.mainCoroutineDispatcher
import com.crosspaste.utils.ioDispatcher
import kotlinx.coroutines.withContext

/** Add our actions to the existing clipboard context menu, not a parallel menu UI. */
class WorkbenchMenuContributor(
    private val controller: ClipboardWorkbench,
    private val reader: PasteItemReader,
    private val paths: UserDataPathProvider,
    private val copywriter: GlobalCopywriter,
) : PasteMenuContributor {
    override fun contribute(pasteData: PasteData): List<PasteMenuAction> {
        if (pasteData.getType() == PasteType.FILE_TYPE) {
            return listOf("copy_file_names", "copy_filetree_markdown", "copy_folder_tree_markdown").mapIndexed {
                index,
                key,
                ->
                PasteMenuAction(copywriter.getText(key)) {
                    val files =
                        withContext(ioDispatcher) {
                            pasteData
                                .getPasteItem(PasteFiles::class)
                                ?.getFilePaths(paths)
                                ?.map { it.toNioPath() }
                                .orEmpty()
                        }
                    if (files.isNotEmpty()) {
                        withContext(mainCoroutineDispatcher.coroutineContext) {
                            controller.openHistoryFiles(files)
                            if (index == 0) controller.copyFileNames() else controller.copyTree(index == 2)
                        }
                    }
                }
            }
        }
        if (pasteData.getType() !in
            setOf(PasteType.TEXT_TYPE, PasteType.URL_TYPE, PasteType.HTML_TYPE, PasteType.RTF_TYPE)
        ) {
            return emptyList()
        }
        return listOf(
            PasteMenuAction(copywriter.getText("edit_merge_clip")) {
                val text = withContext(ioDispatcher) { pasteData.pasteAppearItem?.let(reader::getText).orEmpty() }
                if (text.isNotEmpty()) {
                    withContext(mainCoroutineDispatcher.coroutineContext) {
                        controller.openHistoryEditor(
                            ClipboardHistoryText(
                                pasteData.id,
                                text,
                                pasteData.source ?: "Clipboard",
                            ),
                        )
                    }
                }
            },
        )
    }
}
