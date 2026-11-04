# CrossPaste iOS companion

The iPhone client lives here as a native SwiftUI shell, with the pairing and
sync UI bundled from `web/src/ios`. It uses CrossPaste's existing v2 SAS
pairing, key storage, secure WebSocket authentication, and paste-data format.

## Build

From the repository root:

```sh
./gradlew :core:jsBrowserProductionLibraryDistribution :web:npmInstall
cd web
npm run build:ios
cd ../ios
xcodegen generate
xcodebuild -project CrossPasteIOS.xcodeproj -scheme CrossPasteIOS \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

To install on a personal iPhone, open `CrossPasteIOS.xcodeproj` in Xcode, select
your Personal Team for automatic signing, connect and trust the phone, then run
the `CrossPasteIOS` scheme on that device.

## Current scope

The app pairs with a desktop on the same network using its IP address and port,
then syncs text while the app is open. Clipboard reads and writes happen only
after the user taps the corresponding buttons. iOS suspends background apps, so
continuous clipboard monitoring is not available. Images and files are not yet
handled by this first companion version.
