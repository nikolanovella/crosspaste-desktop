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

The receiving flow and encrypted HTTP consent boundary are tested.
`ClipboardPeerClient` obtains a fresh challenge for each request and rejects
responses that do not authenticate to that exact request. Its production adapter
uses the existing encrypted client and peer route resolver, including registered
Bluetooth routes. `ClipboardInvitationSender` prepares and sends offers, then
confirms their status without granting remote consent. Both are registered in
desktop DI; the encrypted HTTP test exercises the complete sending and receiving
service flow. Sending controls are not connected yet, and the running app has not
been updated with this experimental UI. Membership propagation to additional
existing members also remains to be implemented.

## Missing-history catch-up

`ClipboardHistoryService` serves bounded missing-event batches only to explicitly
approved members of an enabled clipboard. Being paired is insufficient. The
encrypted `/clipboard/history` route uses a fresh authenticated challenge and
binds its response to the exact request. `ClipboardHistoryPuller` derives its
cursor from locally verified records and commits original-author signatures to
the append-only SQL store; no incoming OS clipboard selection occurs here.

Tests cover two offline replicas with 137 records, bounded bidirectional catch-up,
restart, replay, holes in origin sequences, invalid cursors, disabled membership,
and atomic rejection of a batch containing one bad signature. The encrypted HTTP
invitation test also checks history denial before joining and successful catch-up
after local consent. File/image materialization, the shared-history UI and the
recurring runtime worker remain pending.

`/clipboard/payload` transfers frozen capture metadata in bounded byte chunks,
only when a verified signed event in that clipboard references the hash. The
client validates offsets, stable total size and a caller-supplied metadata budget.
The desktop receiver writes private staging files, verifies the complete SHA-256
and original capture identity before committing SQL, and removes staged bytes on
success, interruption or rejection. Unicode spanning chunk boundaries is covered
by byte-level assembly. This transfers captured metadata, including inline text;
it does not yet transfer the image/file bytes referenced by that metadata.

`DesktopClipboardBlobStore` provides the private storage primitive for those
bytes. It streams regular files into staging, checks the captured size and
legacy content hash, computes SHA-256, then publishes atomically under that
digest. Copies deduplicate, corrupted cached content can be repaired from a
verified source, and range reads allocate at most 1 MiB. Empty files are supported.
The caller supplies the copy budget; no source file is moved or deleted.
Tests cover multi-chunk data, later source edits/moves, restart, budget/hash
rejection, cache repair and range/digest validation. This primitive is not yet
called by capture or exposed over the network: signed manifest authorization,
network retrieval and native file/image landing still need to be connected.

## Original-author file manifests

File manifests bind every primary and alternate file representation to its original
signed capture, including empty directories and parent relationships. The original
author signs a separate domain containing the metadata hash and content-addressed
blob descriptors. A relay cannot replace that signature with its own identity.
The local snapshotter verifies both the device ID and approved signing key before
resolving any local source path, then verifies captured sizes and hashes while
copying bytes into private storage. Caller budgets are checked before file lookup.
Logical remote names are metadata, not trusted destination filesystem paths.

The private desktop manifest store persists the original signature using a flushed
temporary file and atomic publication. Records are keyed by clipboard, origin event
and metadata hash, and revalidated against current approved membership on load.
An existing committed manifest cannot be replaced by a conflicting valid manifest.
Snapshots reuse a saved manifest only when every referenced private blob still
verifies; moving or editing the original then does not change the copied content.
Caller byte budgets still apply before cached content can be used.

The source server exposes authenticated, encrypted `file-manifest` and `file-blob`
requests. Each request requires an enabled clipboard, paired approved requester,
verified original journal authors and a current undeleted capture head. Retained
historical events alone do not grant file access. Manifests transfer in bounded
chunks; blob range requests must name a digest in that capture's original-signed
manifest. A relay serves the original signature instead of signing as the author.

The source receiver assembles bounded manifest chunks and verifies the original
signature before downloading referenced blobs. Each file streams through private
staging; size, captured Murmur hash and full SHA-256 must match before atomic blob
publication. Interrupted or invalid transfers remove their staging file. Already
verified blobs can be reused, but both manifest and total file budgets still apply.
Only after every representation is present is the signed manifest committed.

Receiving requires an enabled local workspace, paired approved source and the exact
active event/metadata already accepted into local history. This is checked again
before manifest publication. It does not write the OS clipboard or create native
history rows itself.

The desktop landing service places complete trees under a private per-event
directory and rewrites both primary and alternate file representations to local
paths. Sender paths are never used as destinations. Portable filenames avoid
Windows device names, forbidden characters, trailing dots/spaces and sibling
collisions across case/Unicode normalization. Directories, including empty child
directories, are created with the verified files before an atomic directory rename.
Reuse verifies exact tree membership and hashes, rejecting unexpected files or links.

Native history materialization now calls this landing service for file-backed
items instead of leaving every file pending. Items still wait when bytes are
missing. A fresh frontier check inside the SQL publication transaction prevents
an item deleted during file preparation from becoming a new loaded row. File
placement and native history services remain source-only; recurring runtime
setup and the complete global cache/deletion policy are unfinished.

## Replica background runtime

The desktop source now starts a managed replica runtime after normal sync startup
and cancels/joins it before storage shutdown. With no explicitly enabled workspace
it performs no replication. A bounded metadata tick drains captured records, pulls
approved connected replicas, retrieves missing frozen metadata and materializes
ready native history. Rotating batches avoid repeatedly considering only the first
pending events. A separate two-worker, 64-slot queue handles original file snapshots
and incoming files so large file operations do not block metadata ticks. Pending
event IDs are deduplicated and cancellation releases them for later retry.

The runtime never selects the OS clipboard, changes contribution preferences or
approves members. Failed peer history requests do not discard accepted local history
or stop other ready work. Status flows expose tick results and file failure counts.
The existing recording/paste switches remain independent. No workspace is created
or enabled automatically during startup.

This is not yet installed. Before enabling the full product flow, finish native
setup/membership propagation, shared deletion/cache policy and coexistence with
legacy live sync so a copy cannot appear twice through the two protocols.

## Native history projection

`ClipboardHistoryMaterializer` verifies the journal and frozen payload again before
publishing inline content into the existing native history table. Migration 12
adds an event-to-row mapping and durable pending-render flag. A reconnect reuses
that mapping; matching local captures reuse their existing row. Creation, mapping
and retention commit together. Rendering runs afterward and remains retryable if
the renderer fails. Original and edited captures keep separate rows. This service
does not touch the OS clipboard or relay received records as new local captures.

The whole resolved frontier is computed in one pass instead of rescanning the
journal for each entry. This removes that quadratic scan; it does not establish
large signed-history performance for the remaining snapshot-based services.
File/image metadata, including alternate representations of inline content,
remains pending until all of its bytes can be materialized. The
shared-history UI, cache/deletion policy and recurring runtime still need wiring;
the service is registered but is not activated in the installed app.

## Required before live use

- Complete approved-membership propagation and lifecycle integration. The
  invitation and history services now use the SQL resolver, but newly admitted
  identities still need verified propagation to the other existing replicas.
- Wire the capture consumer into the app lifecycle after payload retention and
  approved membership are ready; finish edits/deletions and incoming materialization.
- Finish immutable snapshots of external/indirect file references and safe global
  reference release before enabling capture in the product. Managed owned-file
  retention is in source, but metadata alone does not protect external files.
- Finish immutable image/file blob transfer separately from the implemented
  captured-metadata transfer, and connect both to incoming materialization.
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
