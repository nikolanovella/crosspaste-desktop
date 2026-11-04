package com.crosspaste.workbench

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Redo
import com.composables.icons.materialsymbols.rounded.Undo
import com.crosspaste.app.DesktopAppWindowManager
import com.crosspaste.app.WindowTrigger
import com.crosspaste.ui.Route
import com.crosspaste.ui.base.GeneralIconButton
import com.crosspaste.ui.base.SectionHeader
import com.crosspaste.ui.settings.SettingSectionCard
import com.crosspaste.ui.theme.AppUISize.medium
import com.crosspaste.ui.theme.AppUISize.small
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object ClipboardWorkbenchRoute : Route {
    override val name = "clipboard_workbench"
}

/** A regular main-app page: shares CrossPaste's sidebar, theme, cards and navigation. */
@Composable
fun ClipboardWorkbenchContent() {
    val controller = koinInject<ClipboardWorkbench>()
    val windows = koinInject<DesktopAppWindowManager>()
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) {
        if (state.mode == ClipboardWorkbenchMode.CLOSED) controller.openEditor(showInApp = false)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(small)) {
        SectionHeader(
            if (state.mode ==
                ClipboardWorkbenchMode.COPY
            ) {
                "copy_tools"
            } else {
                "editor_document"
            },
            trailingContent = {
                TextButton(onClick = { windows.showSearchWindow(WindowTrigger.MENU) }) { Text("Clipboard history") }
            },
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.mode == ClipboardWorkbenchMode.COPY) {
            CopySelectionContent(state, controller)
        } else {
            EditorContent(state, controller)
        }
    }
}

@Composable
private fun CopySelectionContent(
    state: ClipboardWorkbenchState,
    controller: ClipboardWorkbench,
) {
    SettingSectionCard {
        Column(Modifier.padding(medium), verticalArrangement = Arrangement.spacedBy(small)) {
            Text(
                if (state.selection.files.isNotEmpty()) {
                    "${state.selection.files.size} selected files/folders"
                } else {
                    state.selection.text
                },
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.selection.canCopyNormally) {
                Button(onClick = controller::copyNormally, enabled = !state.busy) { Text("Copy normally") }
            }
            if (state.selection.text.isNotEmpty()) {
                OutlinedButton(
                    onClick = controller::copyPlainText,
                    enabled = !state.busy,
                ) { Text("Copy as plain text") }
                OutlinedButton(onClick = {
                    controller.openEditor(fromSelection = true)
                }, enabled = !state.busy) { Text("Edit / merge") }
            }
            if (state.selection.files.isNotEmpty()) {
                OutlinedButton(
                    onClick = controller::copyFileNames,
                    enabled = !state.busy,
                ) { Text("Copy list of file names") }
                OutlinedButton(
                    onClick = { controller.copyTree(false) },
                    enabled = !state.busy,
                ) { Text("Copy filetree as Markdown") }
                OutlinedButton(onClick = {
                    controller.copyTree(true)
                }, enabled = !state.busy) { Text("Copy folder hierarchy as Markdown") }
            }
            if (state.busy) TextButton(onClick = controller::cancel) { Text("Cancel") }
            TextButton(onClick = { controller.openEditor() }) { Text("Open editor") }
        }
    }
}

@Composable
private fun EditorContent(
    state: ClipboardWorkbenchState,
    controller: ClipboardWorkbench,
) {
    var library by remember { mutableStateOf(false) }
    var originals by remember { mutableStateOf(false) }
    var selectedHistory by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var filter by remember { mutableStateOf("") }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(small)) {
        Button(onClick = { controller.saveEdited() }, enabled = !state.busy) { Text("Save & copy") }
        OutlinedButton(
            onClick = { controller.saveEdited(paste = true) },
            enabled = !state.busy && controller.hasPasteTarget(),
        ) { Text("Save & paste") }
        GeneralIconButton(MaterialSymbols.Rounded.Undo, desc = "editor_undo", onClick = controller::undo)
        GeneralIconButton(MaterialSymbols.Rounded.Redo, desc = "editor_redo", onClick = controller::redo)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(small)) {
        FilterChip(selected = !library, onClick = { library = false }, label = { Text("Editor") })
        FilterChip(selected = library, onClick = {
            library = true
            controller.refreshHistory()
        }, label = { Text("Merge clips") })
        FilterChip(selected = originals, onClick = { originals = !originals }, label = { Text("Show originals") })
    }
    if (library) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            label = { Text("Filter clipboard history") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            controller.mergeHistory(state.history.filter { it.id in selectedHistory })
            selectedHistory = emptySet()
            library = false
        }, enabled = selectedHistory.isNotEmpty()) { Text("Merge selected as a group") }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(small)) {
            items(state.history.filter { it.text.contains(filter, true) }, key = { it.id }) { clip ->
                SettingSectionCard {
                    Row(Modifier.padding(small)) {
                        Checkbox(checked = clip.id in selectedHistory, onCheckedChange = {
                            selectedHistory = if (it) selectedHistory + clip.id else selectedHistory - clip.id
                        })
                        Column(Modifier.weight(1f)) {
                            Text(clip.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(clip.source, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = {
                                controller.append(clip)
                                library = false
                            }) { Text("Add to editor") }
                        }
                    }
                }
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(small)) {
            TextButton(onClick = controller::blank) { Text("Add block") }
            TextButton(onClick = controller::group, enabled = state.selected.size > 1) { Text("Group") }
            TextButton(onClick = controller::ungroup, enabled = state.selected.isNotEmpty()) { Text("Ungroup") }
            TextButton(onClick = controller::remove, enabled = state.selected.isNotEmpty()) { Text("Remove") }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(small)) {
            if (state.document.blocks.isEmpty()) {
                item {
                    Text(
                        "Add a block or choose Merge clips. Originals are preserved.",
                    )
                }
            }
            items(state.document.blocks, key = { it.id }) { block ->
                val original = state.document.originals.firstOrNull { it.id == block.originalId }
                SettingSectionCard(Modifier.onGloballyPositioned { bounds[block.id] = it.boundsInRoot() }) {
                    Column(Modifier.padding(small), verticalArrangement = Arrangement.spacedBy(small)) {
                        Row {
                            Checkbox(checked = block.id in state.selected, onCheckedChange = {
                                controller.select(if (it) state.selected + block.id else state.selected - block.id)
                            })
                            Text(
                                "⠿ Drag${if (block.groupId != null) " · Group" else ""}",
                                Modifier.padding(small).pointerInput(block.id, state.selected, state.document.blocks) {
                                    var y = 0f
                                    detectDragGestures(
                                        onDragStart = { y = (bounds[block.id]?.top ?: 0f) + it.y },
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
                        }
                        Text(original?.source ?: "New block", style = MaterialTheme.typography.labelSmall)
                        OutlinedTextField(
                            value = block.text,
                            onValueChange = { controller.edit(block.id, it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        if (originals && original != null) {
                            Text("Original — unchanged", style = MaterialTheme.typography.labelSmall)
                            OutlinedTextField(
                                value = original.text,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                            TextButton(
                                onClick = { controller.edit(block.id, original.text) },
                            ) { Text("Restore original") }
                        }
                    }
                }
            }
        }
    }
}
