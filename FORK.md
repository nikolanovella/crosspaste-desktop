# CrossPaste — Nikola's fork

Personal Windows–Mac clipboard workspace, forked from
[CrossPaste/crosspaste-desktop](https://github.com/CrossPaste/crosspaste-desktop).

## Start here

| Need | Go to |
| --- | --- |
| Install the current app on Windows or Mac | [Official downloads](https://crosspaste.com/en/download) |
| Understand the source layout | [Code map](doc/fork/CODE-MAP.md) |
| Develop, test, or update this fork | [Development guide](doc/fork/DEVELOPMENT.md) |
| Use the command-line interface | [Upstream CLI guide](doc/en/CLI.md) |
| Read the existing feature plans | [Upstream roadmap](doc/en/Roadmap.md) |
| Check the license | [AGPL-3.0 license](LICENSE) |

## Current status

- Fork created on November 4, 2026, from upstream commit
  `3ff3e5fc` (`use the local paste id when writing a received non-file paste to the pasteboard`).
- The [Clipboard Workbench](doc/fork/CLIPBOARD-WORKBENCH.md) adds paired copy/paste shortcuts, three file-copy tools, and a non-destructive text/Markdown block editor.
- Pairing, encryption, and the cross-device protocol retain the upstream implementations.
- Windows Workbench preview is packaged and installed locally for live testing.
  The feature branch builds matching Windows, Apple Silicon Mac, and Intel Mac
  portable previews in the Clipboard Workbench builds workflow. These are
  development artifacts, not a signed/notarized production release.
- JVM compilation and focused regression tests have passed on Windows. Native Mac runtime and cross-device pairing remain separate verification steps.

## Working layout

**Build naming requirement:** Always include the target OS in every distributable
app/package, installer, archive, artifact, and release download name. Include the
architecture too: for example `CrossPaste-Workbench-Windows-x64`,
`CrossPaste-Workbench-macOS-AppleSilicon`, and `CrossPaste-Workbench-macOS-Intel`.
Never publish an ambiguously named cross-platform download.

Keep upstream modules and package paths in place so later upstream fixes remain
straightforward to merge. Fork-specific documentation lives in `doc/fork/`.
Feature work belongs in the existing module that owns the behavior, on a focused
`feature/<name>` or `fix/<name>` branch.

`origin` is this personal fork. `upstream` is the original project. The local
working copy disables pushes to `upstream`; normal pushes go to `origin`.
New clones must configure their own remotes as described in the development guide.

## First Windows–Mac check

1. Install the official desktop app on both machines and connect them to the same LAN.
2. Follow the application's device discovery and pairing flow. Grant only the
   operating-system permissions the app requests for features you intend to use.
3. Test plain text in both directions, then an image and a small disposable file.
4. Check that clipboard synchronization still works after reopening the apps.
5. Record the two versions before testing custom builds. Avoid running an installed
   copy and a development copy against the same clipboard simultaneously.

User clipboard contents, paired-device identities, keys, and local clipboard
databases do not belong in this repository. Existing upstream ignore rules retain
the local development data directory outside version control.
