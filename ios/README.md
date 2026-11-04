# CrossPaste iOS companion

This directory contains the native iPhone client for the CrossPaste fork. It is
kept separate from the Compose Desktop app and is built with XcodeGen and Xcode.

Generate `CrossPasteIOS.xcodeproj` here with `xcodegen generate`, then open it in
Xcode. Select your Personal Team for automatic signing to install on your phone.

The current UI is an early shell with the Devices and manual connection flow.
Clipboard sync must use CrossPaste's existing pairing, encryption, and transfer
protocol; do not send clipboard contents as plain text over the LAN.
