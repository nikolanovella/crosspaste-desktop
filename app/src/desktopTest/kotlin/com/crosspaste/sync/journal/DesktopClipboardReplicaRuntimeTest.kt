package com.crosspaste.sync.journal

import com.crosspaste.test.IntegrationTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@IntegrationTest
class DesktopClipboardReplicaRuntimeTest {
    private fun native(f: FileReceiverFixture): ClipboardHistoryMaterializer {
        val landing =
            DesktopClipboardFileLanding(
                f.root.resolve("landings"),
                f.receivedManifests,
                f.capture.verifier,
                f.receivedBlobs,
            )
        return ClipboardHistoryMaterializer(
            f.localDatabase,
            f.receivingHistory,
            "b",
            { null },
            { _, _ -> },
            landing::land,
        )
    }

    @Test
    fun `ticks queue files once while native history stays responsive and approved replica completes real transfer`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                val materializer = native(f)
                val keys = f.capture.peers.aStore.secureKeyPair.signKeyPair
                val selection = ClipboardSelection("shared", "a", 1, f.capture.event.change.id)
                val authenticator = ClipboardSelectionAuthenticator("shared") { keys.publicKey }
                f.receivingHistory.selectionStore("shared").ingest(listOf(authenticator.sign(selection, keys.privateKey)))
                val applied = mutableListOf<Long>()
                val pulled = mutableListOf<String>()
                val runtime =
                    DesktopClipboardReplicaRuntime(
                        f.localDatabase,
                        "b",
                        f.receivingHistory,
                        peers = { listOf("unapproved", "a", "a") },
                        drain = { _, _ -> 0 },
                        pull = { _, peer, _ ->
                            pulled.add(peer)
                            error("History temporarily unavailable")
                        },
                        metadata = { _, _, _, _ -> error("Frozen metadata is already present") },
                        snapshot = { _, _, _ -> error("Not a local capture") },
                        receiveFiles = { peer, event, bytes, manifestBudget, budget ->
                            f.receiver().receive(peer, event, bytes, manifestBudget, budget)
                        },
                        materialize = materializer::materialize,
                        applySelection = { paste ->
                            applied.add(paste.id)
                            true
                        },
                    )
                val first = runtime.tick()
                assertEquals(1, first.queuedFiles)
                assertEquals(0, first.created)
                assertEquals(1, first.failures)
                assertEquals(0, runtime.tick().queuedFiles)
                assertEquals(listOf("a", "a"), pulled)
                assertEquals(0, f.requests) // Tick did not wait for file transfer.
                assertTrue(runtime.processNextFile())
                assertNotNull(
                    f.localDatabase.sharedClipboardDatabaseQueries
                        .getProjection("shared", "a", 1)
                        .executeAsOneOrNull(),
                )
                assertFalse(runtime.processNextFile())
                assertEquals(0, runtime.tick().queuedFiles)
                assertEquals(1, applied.size)
                runtime.tick()
                assertEquals(1, applied.size)
                assertEquals(0L, runtime.fileFailures.value)
                f.assertNoPartialFiles()
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `disabling workspace blocks queued files and skips all work until explicitly enabled again`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                val materializer = native(f)
                val runtime =
                    DesktopClipboardReplicaRuntime(
                        f.localDatabase,
                        "b",
                        f.receivingHistory,
                        peers = { listOf("a") },
                        drain = { _, _ -> 0 },
                        pull = { _, _, _ -> 0 },
                        metadata = { _, _, _, _ -> error("Already stored") },
                        snapshot = { _, _, _ -> error("Foreign") },
                        receiveFiles = { _, _, _, _, _ -> error("Disabled workspace must not receive") },
                        materialize = materializer::materialize,
                    )
                assertEquals(1, runtime.tick().queuedFiles)
                f.localDatabase.sharedClipboardDatabaseQueries.putWorkspace("shared", false, false)
                assertTrue(runtime.processNextFile())
                assertEquals(ClipboardReplicaTick(0, 0, 0, 0, 0, 0), runtime.tick())
                assertEquals(0, f.requests)
                assertEquals(0L, runtime.fileFailures.value)
                f.localDatabase.sharedClipboardDatabaseQueries.putWorkspace("shared", true, false)
                assertEquals(1, runtime.tick().queuedFiles)
            } finally {
                f.cleanup()
            }
        }

    @Test
    fun `managed stop cancels active file work waits for cleanup and permits a later retry`() =
        runTest {
            val f = fileReceiverFixture()
            try {
                val materializer = native(f)
                val started = CompletableDeferred<Unit>()
                val cleaned = CompletableDeferred<Unit>()
                val runtime =
                    DesktopClipboardReplicaRuntime(
                        f.localDatabase,
                        "b",
                        f.receivingHistory,
                        peers = { listOf("a") },
                        drain = { _, _ -> 0 },
                        pull = { _, _, _ -> 0 },
                        metadata = { _, _, _, _ -> error("Already stored") },
                        snapshot = { _, _, _ -> error("Foreign") },
                        receiveFiles = { _, _, _, _, _ ->
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cleaned.complete(Unit)
                            }
                        },
                        materialize = materializer::materialize,
                    )
                val job = runtime.start(backgroundScope)
                assertSame(job, runtime.start(backgroundScope))
                started.await()
                runtime.stop()
                assertTrue(cleaned.isCompleted)
                assertTrue(job.isCancelled && job.isCompleted)
                assertEquals(1, runtime.tick().queuedFiles)
                assertEquals(0L, runtime.fileFailures.value)
            } finally {
                f.cleanup()
            }
        }
}
