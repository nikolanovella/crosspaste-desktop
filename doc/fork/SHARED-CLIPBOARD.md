# Shared clipboard replication (in development)

The product presents one shared clipboard, with a local replica on each member
device. Wi-Fi and Bluetooth are interchangeable transports underneath that
collection; neither a public server nor an always-on primary computer is required.

## Current source foundation

`ClipboardJournal` stores immutable, origin-addressed changes. Each change belongs
to a clipboard and entry, has a Lamport clock, and references parent revisions.
Missing-event batches include every origin so a member can relay offline changes.
Acknowledgements advance only through contiguous origin sequences. Duplicate
delivery is harmless; conflicting reuse of an event ID rejects the entire batch.
Concurrent revisions (including deletion versus edit) remain separate heads until
explicitly merged. Replaying an ancestor does not undo deletion.

`SignedClipboardJournal` wraps the core using the existing CrossPaste P-256 signing
implementation. Fixed-field serialization, sorted parents, and a versioned domain
prefix define the signed bytes. Incoming batches verify every original author's
key against caller-supplied trusted membership before any write. Signatures travel
with events through relays and are verified again after restart. This does not
automatically establish membership: a key provided by the event itself is never
accepted as evidence of trust.

`ClipboardPeerAuthentication` authenticates requests using the existing paired
ECDH-derived message-authentication key. Proofs bind the source, target, clipboard,
operation, body and one-use challenge; successful verification consumes the
challenge. Replies use a separate domain and include a SHA-256 digest of the exact
request. A failed proof does not burn a valid challenge. This authenticates the
paired device and message, not approval to join a clipboard.

The desktop server registers `/clipboard/challenge` and `/clipboard/identity`
inside its normal encrypted HTTP stack. These read-only endpoints expose an
authenticated signing identity only to an already paired peer. They do not enroll
members, enable recording, start Bluetooth, or create a clipboard. Invitation,
acceptance and history/payload routes still need implementation.

The encrypted identity round-trip test uses the existing HTTP encryption plugins.
It exposed a sender bug: changing `HttpRequestBuilder.body` did not replace the
send-pipeline subject the engine used. `ClientEncryptPlugin` now forwards the
encrypted subject with `proceedWith`. Wire-level JSON and binary checks verify the
engine receives transformed bytes, rather than merely checking for a successful
mock response.

`ClipboardMembershipStore` persists explicitly approved P-256 public signing keys
per clipboard (migration 11). Creating a clipboard and approving its local member
commit together. Approval is idempotent for the same key and rejects replacement
of an existing device's key. Verification can reload approved identities from SQL
without depending on which transport is connected. This store is not an invitation
verifier: network enrollment still needs paired-peer authentication and a local
approval flow. It never enrolls a key merely because an event contains it.

`DesktopClipboardJournalStore` writes a separate metadata snapshot using a flushed
temporary file and atomic replacement. Storage failure must leave the previous
snapshot and in-memory sequence intact. Corrupt or wrong-clipboard data fails
explicitly rather than resetting history. This is an initial bounded snapshot
store, not yet the scalable indexed journal needed for unlimited history.
`DesktopSignedClipboardStore` retains signatures alongside metadata with the same
atomic replacement behavior; the signed layer verifies recovered data before use.

The local release pipeline now has an opt-in database capture outbox. A workspace
must be explicitly enabled with contribution on; a fresh database has no such
workspace. After processing and image dedup, eligible local content is frozen in
the outbox in the same transaction that marks its history record loaded. Capture
respects local listening and content-type sync switches and excludes received
items and explicitly scoped recipients. The metadata remains available after
ordinary history dedup/deletion. A failed outbox insert rolls back the loaded row.
Migration 8 adds only the new workspace/outbox tables; it does not rewrite history.

`ClipboardCaptureConsumer` processes a bounded capture batch using the original
device's signing key. It hashes frozen metadata with SHA-256 and signs a reference
to that payload. Migration 9 adds indexed, append-only signed events and metadata
payloads. `SqlSignedClipboardStore` commits events, payloads and capture
acknowledgements together. Replayed captures resolve to their original entry;
stale writers are rejected rather than overwriting newer events. Unlike the
initial file-snapshot adapter, SQL storage has no 32 MB snapshot-file cap. The
current reconciliation projection still loads history in memory and needs further
scaling work before claiming large-history performance.

Migration 10 adds per-clipboard retention references when a capture is enqueued,
and backfills pending captures from the earlier schema. Ordinary local-history
cleanup leaves a retained row soft-deleted and its managed files intact. Queue
acknowledgement does not release that reference. Cleanup can reclaim bytes only
after every owning clipboard explicitly releases its reference. Global deletion
and retention policy still need UI/lifecycle integration; this must not become an
unexplained permanent-storage policy. External file references are not snapshots:
retaining their history row does not protect a user-owned file from later edits,
moves or deletion, nor another row's managed files referenced indirectly.

## Explicit invitation approval

`ClipboardInvitationService` stages authenticated invitations from already paired
peers. Proposals and status requests cannot approve membership remotely. The
receiving device has a themed card in Devices with Join clipboard and Not now;
only its local Join action commits the proposed public keys in one transaction.
Conflicting keys roll back the entire enrollment and existing contribution
preferences are preserved. Pending offers expire after ten minutes. After a
restart, an identical offer can acknowledge consent already saved in SQL, but
adding any new member still requires approval.

The receiving flow and encrypted HTTP consent boundary are tested. The outgoing
invitation controller and controls are not connected yet, and the running app has
not been updated with this experimental UI. Membership propagation to additional
existing members also remains to be implemented.

## Required before live use

- Connect the approved SQL membership resolver to the live invitation and sync
  services. Authenticated transport alone does not establish clipboard membership.
- Wire the capture consumer into the app lifecycle after payload retention and
  approved membership are ready; finish edits/deletions and incoming materialization.
- Finish immutable snapshots of external/indirect file references and safe global
  reference release before enabling capture in the product. Managed owned-file
  retention is in source, but metadata alone does not protect external files.
- Transfer and verify missing payloads separately from journal metadata.
- Drain missing batches across reconnects, including Bluetooth transport.
- Separate historical catch-up from the live OS clipboard selection.
- Preserve independent contribute/use settings: received history can remain
  available while automatic clipboard replacement is off.
- Finish outgoing invitation/setup/status controls in the existing themed UI. Reuse existing
  Wi-Fi trust for Bluetooth address exchange, while retaining normal OS pairing
  and permission consent.
- Verify real Windows/Mac offline, reconnection, edit and clipboard flows.

The journal is not yet wired into the running application. Existing paired-device
sync remains active while this new flow is developed.
