# Development and upstream maintenance

[Fork overview](../../FORK.md) · [Code map](CODE-MAP.md)

## Prerequisites

- Git and JDK 21 to bootstrap the Gradle wrapper, matching upstream CI.
- On macOS, Xcode command-line tools for the Swift native bridge.
- Network access for the wrapper, dependencies, and the app's JetBrains Runtime download.
- Node.js 18 or newer only when building the browser extension.

Check the Java installation with `java -version`. Use the committed Gradle
wrapper; a separate global Gradle installation is unnecessary.

## Clone and configure

```sh
git clone https://github.com/nikolanovella/crosspaste-desktop.git
cd crosspaste-desktop
git remote add upstream https://github.com/CrossPaste/crosspaste-desktop.git
git remote set-url --push upstream DISABLED
git remote -v
```

The prepared Windows checkout is at
`C:\Users\Nikola\Documents\Codex\crosspaste-desktop` and already has these remotes.
No machine-specific configuration needs to be committed.

## Commands

Run these from the repository root. First-time dependency downloads can take time.

| Task | macOS/Linux | Windows PowerShell |
| --- | --- | --- |
| Format Kotlin before a build | `./gradlew ktlintFormat` | `.\gradlew.bat ktlintFormat` |
| Check Kotlin style | `./gradlew ktlintCheck` | `.\gradlew.bat ktlintCheck` |
| Run desktop app | `./gradlew :app:run` | `.\gradlew.bat :app:run` |
| Fast desktop tests | `./gradlew :app:desktopTest` | `.\gradlew.bat :app:desktopTest` |
| Include integration tests | `./gradlew :app:desktopTest -PintegrationTests` | `.\gradlew.bat :app:desktopTest -PintegrationTests` |
| Build desktop/core/shared | `./gradlew :core:build :shared:build :app:build` | `.\gradlew.bat :core:build :shared:build :app:build` |
| Build browser extension | `./gradlew :web:build` | `.\gradlew.bat :web:build` |

The existing development-properties template uses `.user` for local development
data. Keep that isolation when testing; do not point development builds at the
installed app's real clipboard history or identity store. Do not enable pairing
protocol experiments merely to change UI styling.

The upstream [contribution guide](../en/Contributing.md),
[commit conventions](../en/CommitMessage.md), and [CLAUDE.md](../../CLAUDE.md)
contain additional project conventions.

## A focused feature change

```sh
git switch main
git pull --ff-only origin main
git switch -c feature/short-description
```

Change the owning module rather than copying production logic into a second
fork-only implementation. Keep shared `commonMain` code platform-independent.
Preserve existing network payloads and trust behavior unless protocol changes are
the explicit purpose of the feature.

Run checks appropriate to the change and inspect `git diff --check` and
`git diff --stat`. Push the branch to this fork:

```sh
git push -u origin feature/short-description
```

State whether a change was only compiled, unit-tested, or actually tested on both
machines. A passing local test does not establish Windows–Mac interoperability.

## Bring in upstream fixes

Start from a clean working tree. Fetching does not merge or overwrite local work:

```sh
git fetch upstream --tags
git log --oneline main..upstream/main
git switch -c maintenance/upstream-update main
git merge upstream/main
```

Resolve conflicts on the maintenance branch, run the relevant checks, and review
the result before merging it into this fork's `main`. Preserve upstream license,
attribution, and package layout. Do not force-push `main` to make an update fit.

## Custom releases

There are no custom release binaries yet. The inherited publishing workflows
reference upstream signing and release infrastructure; they are not ready-made
personal-fork distribution pipelines. Configure a separate release process before
publishing a modified installer, identify it as a fork, and retain the
[license](../../LICENSE).

Until then, use the official desktop release for ordinary clipboard sharing and
development builds only for the feature being tested.
