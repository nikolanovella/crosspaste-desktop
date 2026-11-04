# Clipboard Workbench

## Paired shortcuts

| Action | Windows | macOS |
| --- | --- | --- |
| Copy / cut / paste | Ctrl+C / X / V | Cmd+C / X / V, where supported by the app |
| Paste unformatted | Ctrl+Shift+V | Cmd+Shift+V |
| Context-aware copy menu | Ctrl+Alt+C | Cmd+Option+C |
| Lightweight paste picker | Ctrl+Alt+V | Cmd+Option+V |
| Full clipboard | Ctrl+Alt+Shift+V | Cmd+Option+Shift+V |
| Block editor | Ctrl+Alt+E | Cmd+Option+E |

Shortcuts are configurable in the existing Shortcuts settings. New fork defaults
replace only untouched upstream defaults; existing custom bindings are preserved.
Normal C/X/V commands remain owned by the focused app. macOS Finder does not
support Cmd+X for cutting files; its native move operation is Copy followed by
Cmd+Option+V. This feature does not replace that behavior.

The quick picker stays non-activating: click a clip to paste it into the app that
had focus. A picker opened by the shortcut closes after choosing a clip. Escape
also dismisses it.

## Copy menu

Select something in an application, then invoke the copy menu shortcut. The menu
probes the app's normal Copy command and offers actions for the resulting data:

- **Copy normally** asks the original app to copy again, preserving its formats.
- **Copy as plain text** applies to text selections.
- **Copy List of File Names** applies to actual file/folder selections.
- **Copy Filetree as Markdown** lists selected roots with folders and files.
- **Copy Folder Hierarchy as Markdown** lists folders without ordinary files.

The three file tools share one implementation on both platforms. File-tree
exports read metadata, not file contents. They run in the background, support
cancellation, mark unreadable locations, avoid link/junction cycles, and stop at
100,000 entries rather than producing a silently truncated result. Large exports
must also fit the app's configured non-file clipboard size limit.

A probe does not automatically enqueue selected folders for clipboard file sync.
Choosing Copy normally deliberately returns to the normal capture/sync lifecycle.
Applications that block Copy or expose no selection show an explanation instead
of applying file tools to stale clipboard data. The probe itself leaves the new
selection on the system clipboard; cancelling the menu does not restore the
previous system clipboard. Password-manager concealed-content hints are respected.

## Editor and merge

The Workbench edits **text and Markdown blocks**. Images and binary files remain
available through CrossPaste's normal full clipboard and paste picker.

1. Open a selected text clip in the editor, or open the editor shortcut.
2. Add blank blocks or choose text clips in the history pane. The history pane
   loads the most recent 200 text-convertible entries from a 200-item history query.
3. Check several clips and use **Merge selected** to insert them as grouped blocks.
4. Edit the text, drag block handles to reorder, or use Group/Ungroup. Moving a
   member of a group moves that group together.
5. **Originals** shows immutable source text beside the editable version. Restore
   an original into its derived block without changing the saved original.
6. **Save as new clip & copy** creates a new history item. **Save & paste** also
   returns to the recorded destination before injecting a normal paste. If the
   destination cannot be reactivated, the result stays copied for manual paste.

Drafts, including the immutable originals and edited blocks, are saved locally in
`clipboard-workbench-draft.json` under the app's user-data directory. They reopen
with the editor. Saving a finished clip uses CrossPaste's normal release,
deduplication, and sync pipeline; original history entries are not overwritten.

## File organization

All new production files live together under
`app/src/desktopMain/kotlin/com/crosspaste/workbench/`:

| File | Purpose |
| --- | --- |
| `ClipboardDocument.kt` | Immutable originals and editable/groupable/reorderable blocks |
| `ClipboardFileTools.kt` | Shared file-name and Markdown tree exporters |
| `ClipboardWorkbench.kt` | Clipboard actions, draft persistence, history import, and save lifecycle |
| `ClipboardWorkbenchWindow.kt` | Shared themed menu and editor UI |
| `platform/ClipboardPlatform.kt` | Native adapter contract and selection-capture guard |
| `platform/ClipboardPlatformWindows.kt` | Windows sequence, privacy hints, and destination activation |
| `platform/ClipboardPlatformMac.kt` | Matching macOS implementation |
| `platform/ClipboardShortcutConsumption.kt` | Custom-shortcut consumption and repeat guard |

Tests mirror that folder under `app/src/desktopTest/kotlin/com/crosspaste/workbench/`.
The existing `shortcut_keys/Windows.properties` and `shortcut_keys/Macos.properties`
hold corresponding platform bindings. Shared behavior is implemented once rather
than duplicated into drifting Mac and Windows versions.

## Validation

Run the focused regression suite:

```sh
./gradlew :app:desktopTest --tests 'com.crosspaste.workbench.*' --tests 'com.crosspaste.listener.*' --tests 'com.crosspaste.ui.model.PastePanelViewModelTest'
```

Use `gradlew.bat` on Windows. Building JVM source on Windows compiles both adapter
classes, but does not establish that native Mac focus, accessibility, clipboard
formats, or cross-device syncing work. Those need a real Mac runtime test.
