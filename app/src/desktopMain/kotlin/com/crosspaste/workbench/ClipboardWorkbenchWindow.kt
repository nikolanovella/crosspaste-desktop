package com.crosspaste.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.crosspaste.app.DesktopAppWindowManager
import com.crosspaste.app.WindowTrigger
import com.crosspaste.ui.theme.AppUISize.large
import com.crosspaste.ui.theme.AppUISize.medium
import com.crosspaste.ui.theme.AppUISize.small
import org.koin.compose.koinInject

// One shared editor/menu UI; the paired native adapters live in platform/.
private val EDITOR_WIDTH = 1000.dp
private val EDITOR_HEIGHT = 700.dp
private val COPY_WIDTH = 470.dp
private val COPY_HEIGHT = 490.dp
private val HISTORY_WIDTH = 280.dp

@Composable
fun ClipboardWorkbenchWindow() {
    val controller = koinInject<ClipboardWorkbench>()
    val appWindowManager = koinInject<DesktopAppWindowManager>()
    val state by controller.state.collectAsState()
    if (state.mode == ClipboardWorkbenchMode.CLOSED) return
    val editor = state.mode == ClipboardWorkbenchMode.EDITOR
    val screen =
        remember {
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .maximumWindowBounds
        }
    val width = if (editor) minOf(EDITOR_WIDTH.value, screen.width * 0.9f).dp else COPY_WIDTH
    val height = if (editor) minOf(EDITOR_HEIGHT.value, screen.height - 80f).dp else COPY_HEIGHT
    val windowState = rememberWindowState(width = width, height = height)
    LaunchedEffect(editor) { windowState.size = DpSize(width, height) }
    Window(
        onCloseRequest = controller::close,
        title = if (editor) "CrossPaste — Clipboard Workbench" else "CrossPaste — Copy selection",
        state = windowState,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(medium)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape &&
                        event.type == KeyEventType.KeyUp
                    ) {
                        controller.close()
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(small)) {
                Text(
                    if (editor) "Clipboard Workbench" else "Copy selection",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    controller.close()
                    appWindowManager.showMainWindow(WindowTrigger.MENU)
                }) { Text("Full clipboard") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (editor) WorkbenchEditor(state, controller) else WorkbenchCopyMenu(state, controller)
        }
    }
}

@Composable
private fun WorkbenchCopyMenu(
    state: ClipboardWorkbenchState,
    controller: ClipboardWorkbench,
) {
    Column(verticalArrangement = Arrangement.spacedBy(small)) {
        Text(
            if (state.selection.files.isNotEmpty()) {
                "${state.selection.files.size} selected files/folders"
            } else {
                state.selection.text
                    .take(
                        250,
                    )
            },
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = controller::copyNormally,
            enabled = !state.busy,
        ) { Text("Copy normally · preserve app formats") }
        if (state.selection.text.isNotEmpty()) {
            OutlinedButton(onClick = controller::copyPlainText, enabled = !state.busy) { Text("Copy as plain text") }
            OutlinedButton(onClick = {
                controller.openEditor(fromSelection = true)
            }, enabled = !state.busy) { Text("Edit / merge with other clips…") }
        }
        if (state.selection.files.isNotEmpty()) {
            OutlinedButton(
                onClick = controller::copyFileNames,
                enabled = !state.busy,
            ) { Text("Copy List of File Names") }
            OutlinedButton(
                onClick = { controller.copyTree(false) },
                enabled = !state.busy,
            ) { Text("Copy Filetree as Markdown") }
            OutlinedButton(onClick = {
                controller.copyTree(true)
            }, enabled = !state.busy) { Text("Copy Folder Hierarchy as Markdown") }
        }
        if (state.busy) TextButton(onClick = controller::cancel) { Text("Cancel operation") }
        TextButton(onClick = { controller.openEditor() }, enabled = !state.busy) { Text("Open block editor") }
    }
}

@Composable
private fun WorkbenchEditor(
    state: ClipboardWorkbenchState,
    controller: ClipboardWorkbench,
) {
    var selectedHistory by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showOriginals by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(small)) {
        Button(onClick = { controller.saveEdited() }, enabled = !state.busy) { Text("Save as new clip & copy") }
        OutlinedButton(
            onClick = { controller.saveEdited(paste = true) },
            enabled = !state.busy,
        ) { Text("Save & paste") }
        TextButton(onClick = controller::undo) { Text("Undo") }
        TextButton(onClick = controller::redo) { Text("Redo") }
        TextButton(onClick = {
            showOriginals = !showOriginals
        }) { Text(if (showOriginals) "Hide originals" else "Originals (${state.document.originals.size})") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(small)) {
        TextButton(onClick = controller::blank) { Text("Add block") }
        TextButton(onClick = controller::group, enabled = state.selected.size > 1) { Text("Group selected") }
        TextButton(onClick = controller::ungroup, enabled = state.selected.isNotEmpty()) { Text("Ungroup") }
        TextButton(onClick = controller::remove, enabled = state.selected.isNotEmpty()) { Text("Remove blocks") }
        Text("Drag the handle to reorder. Groups travel together.", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(medium)) {
        Column(Modifier.width(HISTORY_WIDTH).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(small)) {
            Text("Merge from clipboard", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = filter, onValueChange = {
                filter = it
            }, singleLine = true, label = { Text("Filter text clips") }, modifier = Modifier.fillMaxWidth())
            Row {
                Button(onClick = {
                    controller.mergeHistory(state.history.filter { it.id in selectedHistory })
                    selectedHistory = emptySet()
                }, enabled = selectedHistory.isNotEmpty()) { Text("Merge selected") }
                TextButton(onClick = controller::refreshHistory) { Text("Refresh") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(small)) {
                items(state.history.filter { it.text.contains(filter, true) }, key = { it.id }) { clip ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(small)) {
                            Checkbox(checked = clip.id in selectedHistory, onCheckedChange = { checked ->
                                selectedHistory =
                                    if (checked) selectedHistory + clip.id else selectedHistory - clip.id
                            })
                            Column {
                                Text(clip.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(clip.source, style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { controller.append(clip) }) { Text("Add to editor") }
                            }
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(small)) {
            if (state.document.blocks.isEmpty()) {
                item {
                    Text(
                        "Add a blank block or choose clips on the left. Saving creates a new clipboard item.",
                        Modifier.padding(large),
                    )
                }
            }
            items(state.document.blocks, key = { it.id }) { block ->
                val original = state.document.originals.firstOrNull { it.id == block.originalId }
                Card(Modifier.fillMaxWidth().onGloballyPositioned { bounds[block.id] = it.boundsInRoot() }) {
                    Column(Modifier.padding(small), verticalArrangement = Arrangement.spacedBy(small)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(small)) {
                            Checkbox(checked = block.id in state.selected, onCheckedChange = { checked ->
                                controller.select(
                                    if (checked) {
                                        state.selected +
                                            block.id
                                    } else {
                                        state.selected - block.id
                                    },
                                )
                            })
                            Text(
                                "⠿ Drag${if (block.groupId != null) " · Group" else ""}",
                                modifier =
                                    Modifier
                                        .padding(
                                            small,
                                        ).pointerInput(block.id, state.selected, state.document.blocks) {
                                            var y = 0f
                                            detectDragGestures(
                                                onDragStart = { point -> y = (bounds[block.id]?.top ?: 0f) + point.y },
                                                onDrag = { change, delta ->
                                                    change.consume()
                                                    y += delta.y
                                                },
                                                onDragEnd = {
                                                    val destination =
                                                        state.document.blocks
                                                            .firstOrNull {
                                                                (
                                                                    bounds[it.id]?.center?.y
                                                                        ?: Float.MAX_VALUE
                                                                ) >
                                                                    y
                                                            }?.id
                                                    controller.move(
                                                        if (block.id in
                                                            state.selected
                                                        ) {
                                                            state.selected
                                                        } else {
                                                            setOf(block.id)
                                                        },
                                                        destination,
                                                    )
                                                },
                                            )
                                        },
                            )
                            Text(original?.source ?: "New block", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedTextField(value = block.text, onValueChange = {
                            controller.edit(block.id, it)
                        }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        if (showOriginals && original != null) {
                            Text("Original — unchanged", style = MaterialTheme.typography.labelSmall)
                            OutlinedTextField(value = original.text, onValueChange = {
                            }, readOnly = true, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            TextButton(
                                onClick = { controller.edit(block.id, original.text) },
                            ) { Text("Restore original in this block") }
                        }
                    }
                }
            }
            item { Box(Modifier.fillMaxWidth().height(60.dp)) }
        }
    }
}
