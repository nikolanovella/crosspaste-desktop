# Optional Bluetooth connection (experimental)

The desktop fork includes a Bluetooth card on **Devices**. It uses the existing
CrossPaste theme, paired-device list, encrypted protocol and clipboard database.
It does not create a separate clipboard. Recording and shared-paste participation
keep their existing independent switches.

Bluetooth is **off by default**. Opening Devices does not start a radio connection
or publish a service. The link is not added to startup. Leaving Devices after
connecting keeps the link running; Disconnect or quitting CrossPaste stops it.

## Windows and Mac setup

1. Install the matching fork build on both computers. Archives always include the
   target OS and architecture in their names.
2. Pair the computers normally in the operating systems' Bluetooth settings.
3. Pair the same computers in CrossPaste first, for example over the LAN. This
   implementation requires the existing saved encryption key; it cannot create
   or silently approve new trust.
4. On the Mac's Devices page, select the Windows computer and choose **Listen
   for Windows**. Approve macOS Bluetooth permission yourself if requested.
5. On Windows, select the Mac, enter its Bluetooth address, and choose **Connect**.
6. The link checks the selected CrossPaste identity, then the existing encrypted
   resolver authenticates the peer. The normal device row reports sync state.

“Bluetooth link ready” is transport readiness, not a claim that authentication or
clipboard synchronization has completed. A failed Bluetooth authentication does
not erase your existing pairing. Disconnect removes the temporary route, allowing
the normal LAN discovery to resume without rewriting the stored LAN addresses.

## Implementation and builds

- `native/bluetooth/windows`: windowless Windows RFCOMM connector. It requires
  authenticated and encrypted Bluetooth and uses no custom driver.
- `native/bluetooth/macos`: transient RFCOMM service, accepting OS-paired peers.
- `native/bluetooth/common`: the shared service UUID definition.
- `app/src/desktopMain/.../transport/bluetooth`: shared desktop framing, bounded
  bidirectional relay, owned-process lifecycle, controller and Devices card.
- `PeerTransportRoutes`: ephemeral routes keyed by the explicit CrossPaste peer
  identity. Stale sessions cannot delete a newer route. Unknown peers and normal
  LAN requests are not redirected.

Each platform builds its adapter with CMake. CI stages it under
`app/resources/<os>-<architecture>/bluetooth` before the Compose distribution task.
The app resolves it from `compose.application.resources.dir`. macOS distributions
include the Bluetooth permission purpose text; their embedded helper is signed
alongside the app. No helper or radio connection is launched by CI self-tests.

The relay binds only IPv4 loopback and can open only the configured local
CrossPaste server port. It carries requests in both directions across one link,
with bounded streams, frames and receive credits. Transport framing itself is
not authentication; CrossPaste's existing cryptographic verification remains
required above it.

## Verification boundary

Windows and Intel Mac native compilation and radio-free self-tests passed in
run `41846587150`. The Apple Silicon job in that run was cancelled while queued.
Local tests cover framing, simultaneous bidirectional loopback traffic, flow
control, half-close responses, cancellation, disconnect cleanup, peer routing,
wrong-identity rejection, offline discovery and preservation of saved trust.

These tests **do not establish a physical Windows–Mac Bluetooth connection**.
Real OS permission/pairing, Wi-Fi-disabled transfer, reconnect after sleep, and
bidirectional text/image/file behavior still require testing on the paired Mac.
Bluetooth's throughput is lower than Wi-Fi; generous bounded transfer timeouts
are applied only to registered Bluetooth relay endpoints.

Updated Windows CI run `41854073952` passed packaging, native self-test, all
128 protocol/Bluetooth checks and all 36 existing Workbench checks. Its XML
artifact was read back: zero failures, errors or skipped tests in either suite.
The packaged classes and native helper were checked in the local portable build.
The working Windows installation has not been replaced while its desktop is
locked, so the new Devices card remains unverified through real screen control.

Both Mac jobs in that run failed to acquire hosted runners. Mac-only retry
`41857764581` used the alternate `macos-15` Apple Silicon and `macos-26-intel`
pools; both again failed before executing any build step. GitHub's annotations
explicitly report runner acquisition failure and arm64 capacity constraints.
This is an outstanding build-verification gap, not a successful Mac build.
