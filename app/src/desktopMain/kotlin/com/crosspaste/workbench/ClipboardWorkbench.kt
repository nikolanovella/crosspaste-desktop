package com.crosspaste.workbench

import com.crosspaste.app.AppFileType
import com.crosspaste.app.AppInfo
import com.crosspaste.config.DesktopConfigManager
import com.crosspaste.db.paste.PasteDao
import com.crosspaste.paste.PasteReleaseService
import com.crosspaste.paste.PasteboardService
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.paste.item.PasteItemReader
import com.crosspaste.paste.plugin.type.DesktopTextTypePlugin
import com.crosspaste.path.AppPathProvider
import com.crosspaste.platform.Platform
import com.crosspaste.utils.GlobalCoroutineScope.mainCoroutineDispatcher
import com.crosspaste.utils.ioDispatcher
import com.crosspaste.workbench.platform.ClipboardCaptureGate
import com.crosspaste.workbench.platform.clipboardPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.time.Duration.Companion.milliseconds

enum class ClipboardWorkbenchMode { CLOSED, COPY, EDITOR }

data class ClipboardSelection(
    val text: String = "",
    val files: List<Path> = emptyList(),
)

data class ClipboardHistoryText(
    val id: Long,
    val text: String,
    val source: String,
)

data class ClipboardWorkbenchState(
    val mode: ClipboardWorkbenchMode = ClipboardWorkbenchMode.CLOSED,
    val selection: ClipboardSelection = ClipboardSelection(),
    val document: ClipboardDocument = ClipboardDocument(),
    val selected: Set<String> = emptySet(),
    val history: List<ClipboardHistoryText> = emptyList(),
    val message: String = "",
    val busy: Boolean = false,
)

class ClipboardWorkbench(
    private val appInfo: AppInfo,
    private val configManager: DesktopConfigManager,
    private val pasteDao: PasteDao,
    private val releaseService: PasteReleaseService,
    private val pasteboardService: PasteboardService,
    private val itemReader: PasteItemReader,
    private val paths: AppPathProvider,
    platform: Platform,
) {
    private val native = clipboardPlatform(platform)
    private val mutableState = MutableStateFlow(ClipboardWorkbenchState())
    val state = mutableState.asStateFlow()
    private var target: (() -> Boolean)? = null
    private var operation: Job? = null
    private var persistJob: Job? = null
    private var loaded = false
    private val persistMutex = Mutex()
    private val undo = ArrayDeque<ClipboardDocument>()
    private val redo = ArrayDeque<ClipboardDocument>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun openCopyTools() {
        if (state.value.busy) return
        target = native.captureTarget()
        mutableState.value = state.value.copy(busy = true, message = "Reading selection…")
        val selection =
            runCatching {
                withContext(ioDispatcher) {
                    val before = native.sequence()
                    ClipboardCaptureGate.active = true
                    try {
                        native.copySelection()
                        val changed =
                            withTimeoutOrNull(1500.milliseconds) {
                                while (native.sequence() == before) delay(25.milliseconds)
                                true
                            } ?: false
                        check(
                            changed,
                        ) {
                            "This app did not expose a new selection. Copy it normally, then use CrossPaste's history."
                        }
                        check(!native.concealed()) { "This clipboard item is marked private and cannot be imported." }
                        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                        val files =
                            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                (
                                    contents.getTransferData(
                                        DataFlavor.javaFileListFlavor,
                                    ) as? List<*>
                                )?.filterIsInstance<File>()
                                    ?.map {
                                        it.toPath()
                                    }.orEmpty()
                            } else {
                                emptyList()
                            }
                        val text =
                            if (files.isEmpty() &&
                                contents.isDataFlavorSupported(DataFlavor.stringFlavor)
                            ) {
                                contents.getTransferData(DataFlavor.stringFlavor) as? String
                                    ?: ""
                            } else {
                                ""
                            }
                        ClipboardSelection(text, files)
                    } finally {
                        ClipboardCaptureGate.finish(native.sequence())
                    }
                }
            }
        mutableState.value =
            state.value.copy(
                mode = ClipboardWorkbenchMode.COPY,
                busy = false,
                selection = selection.getOrNull() ?: ClipboardSelection(),
                message = selection.exceptionOrNull()?.message ?: "Choose how to copy this selection",
            )
    }

    suspend fun pastePlainFromSystem() {
        target = native.captureTarget()
        val text =
            withContext(ioDispatcher) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                check(
                    clipboard.isDataFlavorSupported(DataFlavor.stringFlavor),
                ) { "Clipboard has no plain-text representation" }
                clipboard.getTransferData(DataFlavor.stringFlavor) as String
            }
        val item = createTextPasteItem(identifiers = listOf(DesktopTextTypePlugin.TEXT), text = text)
        withContext(ioDispatcher) {
            pasteboardService.tryWritePasteboard(id = null, pasteItem = item, localOnly = true).getOrThrow()
        }
        if (restoreTarget()) {
            withContext(ioDispatcher) {
                val robot = java.awt.Robot()
                robot.keyPress(native.primaryKey)
                robot.keyPress(java.awt.event.KeyEvent.VK_V)
                robot.keyRelease(java.awt.event.KeyEvent.VK_V)
                robot.keyRelease(native.primaryKey)
            }
        }
    }

    fun close() {
        operation?.cancel()
        mutableState.value =
            state.value.copy(mode = ClipboardWorkbenchMode.CLOSED, busy = false)
    }

    fun cancel() {
        operation?.cancel()
        mutableState.value =
            state.value.copy(busy = false, message = "Cancelled; no generated result was copied.")
    }

    fun select(ids: Set<String>) {
        mutableState.value = state.value.copy(selected = ids)
    }

    private fun change(
        document: ClipboardDocument,
        recordUndo: Boolean = true,
    ) {
        if (document == state.value.document) return
        if (recordUndo) {
            undo.addLast(state.value.document)
            if (undo.size > 100) undo.removeFirst()
            redo.clear()
        }
        mutableState.value = state.value.copy(document = document)
        persistJob?.cancel()
        persistJob =
            mainCoroutineDispatcher.launch {
                delay(350.milliseconds)
                persist()
            }
    }

    fun edit(
        id: String,
        text: String,
    ) = change(state.value.document.edit(id, text))

    fun group() = change(state.value.document.grouped(state.value.selected))

    fun ungroup() = change(state.value.document.ungroup(state.value.selected))

    fun move(
        ids: Set<String>,
        before: String?,
    ) = change(state.value.document.move(ids, before))

    fun remove() =
        change(
            state.value.document.copy(
                blocks =
                    state.value.document.blocks.filterNot {
                        it.id in
                            state.value.selected
                    },
            ),
        )

    fun undo() {
        if (undo.isNotEmpty()) {
            redo.addLast(state.value.document)
            change(undo.removeLast(), false)
        }
    }

    fun redo() {
        if (redo.isNotEmpty()) {
            undo.addLast(state.value.document)
            change(redo.removeLast(), false)
        }
    }

    fun append(clip: ClipboardHistoryText) =
        change(state.value.document.append(clip.text, "History #${clip.id}: ${clip.source}"))

    fun blank() = change(state.value.document.append("", "New block"))

    fun mergeHistory(clips: List<ClipboardHistoryText>) {
        var document = state.value.document
        val oldIds = document.blocks.map { it.id }.toSet()
        for (clip in clips) document = document.append(clip.text, "History #${clip.id}: ${clip.source}")
        val added =
            document.blocks
                .map { it.id }
                .filterNot { it in oldIds }
                .toSet()
        change(document.grouped(added))
        select(added)
    }

    fun openEditor(
        fromSelection: Boolean = false,
        captureTarget: Boolean = false,
    ) {
        if (captureTarget) target = native.captureTarget()
        mainCoroutineDispatcher.launch {
            if (!loaded) {
                loaded = true
                val saved =
                    withContext(ioDispatcher) {
                        runCatching {
                            json.decodeFromString<ClipboardDocument>(
                                draftPath().toFile().readText(),
                            )
                        }.getOrNull()
                    }
                if (saved != null) mutableState.value = state.value.copy(document = saved)
            }
            if (fromSelection &&
                state.value.selection.text
                    .isNotEmpty()
            ) {
                change(state.value.document.append(state.value.selection.text, "Selected text"))
            }
            mutableState.value =
                state.value.copy(
                    mode = ClipboardWorkbenchMode.EDITOR,
                    message = "Edits are saved as a separate draft. Originals stay intact.",
                )
            refreshHistory()
        }
    }

    fun refreshHistory() {
        mainCoroutineDispatcher.launch {
            runCatching {
                withContext(ioDispatcher) {
                    pasteDao.searchPasteData(searchTerms = emptyList(), limit = 200).mapNotNull { paste ->
                        val item = paste.pasteAppearItem ?: return@mapNotNull null
                        val text = itemReader.getText(item)
                        text.takeIf { it.isNotEmpty() }?.let {
                            ClipboardHistoryText(
                                paste.id,
                                it,
                                paste.source ?: "Clipboard",
                            )
                        }
                    }
                }
            }.onSuccess { mutableState.value = state.value.copy(history = it) }
                .onFailure { mutableState.value = state.value.copy(message = "Could not load history: ${it.message}") }
        }
    }

    private fun draftPath() = paths.resolve("clipboard-workbench-draft.json", AppFileType.USER)

    private suspend fun persist() {
        persistMutex.withLock {
            val document = state.value.document
            withContext(ioDispatcher) {
                runCatching {
                    val path = draftPath().toFile().toPath()
                    Files.createDirectories(path.parent)
                    val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
                    Files.writeString(temporary, json.encodeToString(document))
                    Files.move(temporary, path, REPLACE_EXISTING)
                }.onFailure {
                    mutableState.value =
                        state.value.copy(
                            message = "Draft could not be saved: ${it.message}",
                        )
                }
            }
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (state.value.busy) return
        val originalMode = state.value.mode
        mutableState.value = state.value.copy(busy = true, message = "Working…")
        operation =
            mainCoroutineDispatcher.launch {
                try {
                    block()
                } catch (
                    e: CancellationException,
                ) {
                    throw e
                } catch (
                    e: Exception,
                ) {
                    mutableState.value =
                        state.value.copy(
                            mode =
                                if (state.value.mode ==
                                    ClipboardWorkbenchMode.CLOSED
                                ) {
                                    originalMode
                                } else {
                                    state.value.mode
                                },
                            message = e.message ?: "Operation failed",
                        )
                } finally {
                    mutableState.value = state.value.copy(busy = false)
                }
            }
    }

    fun copyFileNames() =
        runOperation {
            storeText(ClipboardFileTools.names(state.value.selection.files))
            mutableState.value =
                state.value.copy(message = "File names copied")
        }

    fun copyTree(foldersOnly: Boolean) =
        runOperation {
            val result = withContext(ioDispatcher) { ClipboardFileTools.tree(state.value.selection.files, foldersOnly) }
            storeText(result)
            mutableState.value = state.value.copy(message = "Markdown copied")
        }

    fun copyPlainText() =
        runOperation {
            storeText(state.value.selection.text)
            mutableState.value =
                state.value.copy(message = "Plain text copied")
        }

    fun copyNormally() =
        runOperation {
            mutableState.value = state.value.copy(mode = ClipboardWorkbenchMode.CLOSED)
            check(restoreTarget()) { "Could not return to the original app. Copy there normally." }
            withContext(ioDispatcher) { native.copySelection() }
        }

    fun saveEdited(paste: Boolean = false) =
        runOperation {
            val document = state.value.document
            check(document.blocks.isNotEmpty()) { "Add a block first." }
            // Store source texts as distinct history entries before the derived result.
            for (original in document.originals.filter { it.text.isNotEmpty() }) {
                storeText(
                    original.text,
                    writeClipboard = false,
                )
            }
            storeText(document.render())
            persist()
            mutableState.value = state.value.copy(message = "Saved a new clip; originals preserved")
            refreshHistory()
            if (paste) {
                mutableState.value = state.value.copy(mode = ClipboardWorkbenchMode.CLOSED)
                check(restoreTarget()) { "Saved and copied. Focus your destination and paste normally." }
                withContext(ioDispatcher) {
                    val robot = java.awt.Robot()
                    robot.keyPress(native.primaryKey)
                    robot.keyPress(java.awt.event.KeyEvent.VK_V)
                    robot.keyRelease(java.awt.event.KeyEvent.VK_V)
                    robot.keyRelease(native.primaryKey)
                }
            }
        }

    private suspend fun restoreTarget(): Boolean {
        delay(100.milliseconds)
        val restore = target ?: return false
        return withContext(ioDispatcher) {
            withTimeoutOrNull(1500.milliseconds) {
                while (!restore()) delay(50.milliseconds)
                delay(100.milliseconds)
                true
            } ?: false
        }
    }

    private suspend fun storeText(
        text: String,
        writeClipboard: Boolean = true,
    ) {
        check(text.isNotEmpty()) { "Nothing to copy." }
        val item = createTextPasteItem(identifiers = listOf(DesktopTextTypePlugin.TEXT), text = text)
        val limit = configManager.getCurrentConfig().maxNonFilePasteSize.toLong() * 1024 * 1024
        check(item.size <= limit) { "Generated text exceeds CrossPaste's configured size limit." }
        withContext(ioDispatcher) {
            val id =
                releaseService.createLocalPasteData(
                    appInstanceId = appInfo.appInstanceId,
                    pasteItem = item,
                    source = "Clipboard Workbench",
                )
            if (writeClipboard) {
                pasteboardService
                    .tryWritePasteboard(
                        id = id,
                        pasteItem = item,
                        localOnly = true,
                    ).getOrThrow()
            }
        }
    }
}
