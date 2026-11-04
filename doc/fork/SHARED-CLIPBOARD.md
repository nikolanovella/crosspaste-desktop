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

## Required before live use

- Connect explicitly approved clipboard membership to the signature-verification
  resolver. Authenticated transport alone does not establish that membership.
- Finish the transactional outbox consumer, edits/deletions, and durable incoming
  reconciliation with the existing database and clipboard lifecycle.
- Retain file payloads referenced by pending captures before enabling capture in
  the product; preserving metadata alone does not prevent file cleanup.
- Transfer and verify missing payloads separately from journal metadata.
- Drain missing batches across reconnects, including Bluetooth transport.
- Separate historical catch-up from the live OS clipboard selection.
- Preserve independent contribute/use settings: received history can remain
  available while automatic clipboard replacement is off.
- Add Join clipboard/setup/status to the existing themed UI. Reuse existing
  Wi-Fi trust for Bluetooth address exchange, while retaining normal OS pairing
  and permission consent.
- Verify real Windows/Mac offline, reconnection, edit and clipboard flows.

The journal is not yet wired into the running application. Existing paired-device
sync remains active while this new flow is developed.
