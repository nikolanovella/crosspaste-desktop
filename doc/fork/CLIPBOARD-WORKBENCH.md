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

The editor and selection-copy tools are pages inside the original CrossPaste
main app. They use its sidebar, theme, section cards and controls; there is no
separate Workbench window. The existing history context menu also exposes
Edit / merge for text clips and the three copy tools for file clips.

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
| `ClipboardWorkbenchContent.kt` | Native main-app editor and copy-tools page |
| `WorkbenchMenuContributor.kt` | Actions in the existing clipboard history context menu |
| `platform/ClipboardPlatform.kt` | Native adapter contract and selection-capture guard |
| `platform/ClipboardPlatformWindows.kt` | Windows sequence, privacy hints, and destination activation |
| `platform/ClipboardPlatformMac.kt` | Matching macOS implementation |
| `platform/ClipboardShortcutConsumption.kt` | Custom-shortcut consumption and repeat guard |

Tests mirror that folder under `app/src/desktopTest/kotlin/com/crosspaste/workbench/`.
The existing `shortcut_keys/Windows.properties` and `shortcut_keys/Macos.properties`
hold corresponding platform bindings. Shared behavior is implemented once rather
than duplicated into drifting Mac and Windows versions.

## Shared clipboard participation

Under Settings → Clipboard, each Windows/Mac device has two independent controls:

- **Add my copies to the shared clipboard:** reuses the existing local recording
  switch. Off stops automatic capture/contribution, not normal local Copy.
- **Use the shared clipboard when I paste:** new shared clips may replace this
  device's OS clipboard only when enabled. Off retains incoming shared history
  and explicit history/editor paste actions, without changing normal local Paste.

Both default on for new desktop configurations; existing recording preferences
are retained. The receive queue checks paste participation when it executes, so
turning the setting off also blocks an already-queued automatic clipboard write.
This separates recording from the old receive gate, which used the recording flag.

Relaunching the desktop executable activates its running instance through the
same-user local socket. `POST /cli/ui/open?page=main|devices|settings|workbench`
is local-only, rejects arbitrary page names, and is unavailable in headless mode.

Bluetooth transport is **not implemented**. LAN discovery/sync remains the only
implemented connection path in this preview; no Bluetooth switch claims otherwise.

## Validation

Run the focused regression suite:

```sh
./gradlew :app:desktopTest --tests 'com.crosspaste.workbench.*' --tests 'com.crosspaste.listener.*' --tests 'com.crosspaste.ui.model.PastePanelViewModelTest'
```

Use `gradlew.bat` on Windows. Building JVM source on Windows compiles both adapter
classes, but does not establish that native Mac focus, accessibility, clipboard
formats, or cross-device syncing work. Those need a real Mac runtime test.

### Initial preview verification, November 4, 2026

- The focused Windows regression suite passed all 29 tests.
- Windows x64, macOS Apple Silicon, and macOS Intel CI checks and portable
  packaging passed in run `41804430365`.
- The Windows preview is installed locally. Its packaged runtime starts and
  loads the pinned OpenSSL library successfully.
- Live screen testing opened the editor, added/typed a block, and saved it as a
  separate clipboard entry. This exposed a dark-background text contrast issue,
  which was corrected and rebuilt.
- Screen control subsequently returned no targetable windows. Visual verification
  of the rebuilt contrast, paste-back, and drag/merge remains pending; passing unit
  tests are not a substitute for those UI checks. Mac native behavior and actual
  Windows–Mac pairing also remain unverified on personal devices.

### Native UI integration, November 4, 2026

The separate editor window has been removed. Editor, merge, originals, grouping,
reordering, and copy tools now use the existing main-app navigation, settings cards,
and controls. Text/file history context menus expose the corresponding actions.

All 34 focused regression tests passed, including the four device-participation
combinations, queued-write policy changes, configuration persistence, and local UI
activation validation. The Windows package is built and installed. The integrated
editor was visually confirmed in the original sidebar/theme, with the draft intact.
Initial click testing was blocked by shell cloaking: Windows reported the main
window visible and on the current desktop, but DWM cloaked it and screen control
could not target it. No Alt+Tab, Stage Manager, or MyDockFinder setting was changed.

The subsequent live native-UI session verified all four recording/shared-paste
switch combinations against the saved configuration, then restored both switches
to on. It also imported a test clip, edited its derived block, displayed its
unchanged original, saved a separate clipboard entry, and merged two selected
history entries into a persisted two-block group. The paired Mac was confirmed
CONNECTED through both the native Devices page and the local device API.

The live drag attempt exposed a gesture conflict with list scrolling. The repair
uses an immediate vertical drag handle, clears offscreen hit-test bounds, and
tests visible drop targets and viewport insertion boundaries. The full focused
suite passed 36 tests before the gesture refinement; the final Workbench suite
passed all 16 tests. The repaired gesture still needs its last live drop test;
foreground access was interrupted before that test could be completed.

Final portable build run `41826195716` passed on Windows x64, macOS Apple Silicon,
and macOS Intel, including all 36 focused tests on each platform. Archives retain
the OS/architecture naming requirement. The new Mac UI is available in those
artifacts; it has not been installed on the personal Mac by this Windows session.

### Completed Windows gesture and paste checks, November 4, 2026

The installed native UI was tested through actual mouse and keyboard input:

- Dragging a block above another changed the persisted draft order.
- Undo restored the original order; Redo restored the moved order.
- Dragging a merged group moved both members together, retaining their group ID
  and relative order. The test draft was restored afterward.
- Opening the editor with its shortcut from an editable Chrome field enabled
  Save & paste. Selecting it returned to that field and inserted the expected
  214-character test document. The result was checked on screen and through
  the field's accessibility text.

These checks close the Windows gesture and editor paste-back verification gap.
They used only existing test clips and a local browser test page. They do not
establish Mac native paste behavior, Bluetooth transport, or bidirectional
text/image/file synchronization across devices.
