package com.crosspaste.sync.journal

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.crosspaste.Database
import com.crosspaste.config.AppConfig
import com.crosspaste.config.CommonConfigManager
import com.crosspaste.db.Int2LongAdapter
import com.crosspaste.db.PasteTaskEntity
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.db.paste.PasteDao
import com.crosspaste.net.routing.bindAuthenticatedRemoteIdentity
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteReleaseService
import com.crosspaste.paste.PasteState
import com.crosspaste.paste.item.CreatePasteItemHelper.createFilesPasteItem
import com.crosspaste.paste.item.CreatePasteItemHelper.createHtmlPasteItem
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.paste.item.PasteText
import com.crosspaste.presist.SingleFileInfoTree
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.task.TaskBuilder
import com.crosspaste.task.TaskSubmitter
import com.crosspaste.utils.getJsonUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardNativeIdentityStoreTest {
    private class Fixture(
        val peers: ClipboardPeerFixture,
    ) {
        val aFactory = TestDriverFactory()
        val bFactory = TestDriverFactory()
        val aDb = createDatabase(aFactory)
        val bDb = createDatabase(bFactory)
        val serializer = SecureKeyPairSerializer()
        val aMembers = ClipboardMembershipStore(aDb, serializer)
        val bMembers = ClipboardMembershipStore(bDb, serializer)
        val aHistory = ClipboardHistoryService(aDb, "a", peers.aStore, serializer)
        val bHistory = ClipboardHistoryService(bDb, "b", peers.bStore, serializer)
        val aIdentities = ClipboardNativeIdentityStore(aDb)
        val bIdentities = ClipboardNativeIdentityStore(bDb)
        val taskBuilder = mockk<TaskBuilder>(relaxed = true)
        val writes = mutableListOf<PasteData>()
        val sharedRenders = mutableListOf<Long>()
        val native = ClipboardHistoryMaterializer(bDb, bHistory, "b", { null }, { id, _ -> sharedRenders.add(id) })
        val dao = mockk<PasteDao>(relaxed = true)
        val service: PasteReleaseService

        init {
            val config = mockk<AppConfig>(relaxed = true)
            every { config.maxNonFilePasteSize } returns 8L
            val manager = mockk<CommonConfigManager>()
            every { manager.getCurrentConfig() } returns config
            coEvery { dao.createPasteData(any(), any()) } coAnswers { insert(bDb, firstArg()) }
            service =
                PasteReleaseService(
                    manager,
                    mockk(relaxed = true),
                    bDb,
                    mockk(relaxed = true),
                    dao,
                    mockk(relaxed = true),
                    emptyList(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    object : TaskSubmitter {
                        override suspend fun submit(block: suspend TaskBuilder.() -> Unit) {
                            taskBuilder.block()
                        }
                    },
                    mockk(relaxed = true),
                    createRemoteInline = { paste ->
                        bIdentities.receiveInline(paste, { null }, { dao.createPasteData(paste, PasteState.LOADED) })
                    },
                )
        }

        fun capture(
            text: String = "same content",
            collection: PasteCollection = PasteCollection(emptyList()),
        ): PasteData {
            val item = createTextPasteItem(text = text)
            val paste =
                PasteData(
                    appInstanceId = "a",
                    pasteAppearItem = item,
                    pasteCollection = collection,
                    pasteType = 0,
                    source = "Editor",
                    size = item.size,
                    hash = item.hash,
                    createTime = 10,
                    pasteState = PasteState.LOADED,
                )
            val id = insert(aDb, paste)
            aDb.sharedClipboardDatabaseQueries.enqueueLoadedLocalCapture(id)
            return paste.copy(id = id)
        }

        suspend fun sign() =
            ClipboardCaptureConsumer(
                aDb,
                "shared",
                "a",
                peers.aStore.secureKeyPair.signKeyPair.privateKey,
                { aMembers.signingKey("shared", it) },
            ).drain()

        suspend fun wire(paste: PasteData): PasteData {
            val exported = aIdentities.outgoing(paste, "a")
            assertNotNull(exported.captureIdentity)
            return getJsonUtils()
                .JSON
                .decodeFromString<PasteData>(
                    getJsonUtils().JSON.encodeToString(exported),
                ).bindAuthenticatedRemoteIdentity("a")
        }

        suspend fun receive(paste: PasteData) {
            service.releaseRemotePasteData(paste) { writes.add(it) }.getOrThrow()
        }

        suspend fun sync() {
            val proof =
                peers.b.prove(
                    "a",
                    "shared",
                    ClipboardPeerOperation.HISTORY,
                    peers.a.issue("b"),
                    Json.encodeToString(ClipboardHistoryRequest(emptyMap())),
                )
            val request = assertNotNull(peers.a.verify("b", proof, ClipboardPeerOperation.HISTORY))
            val response = peers.a.reply(request, Json.encodeToString(aHistory.read(request)))
            bHistory.ingest(assertNotNull(peers.b.verifiedReply("a", proof.request, response)))
            for (event in SqlSignedClipboardStore(aDb, "shared").load().changes) {
                val hash = assertNotNull(event.change.contentHash)
                bHistory.storePayload(
                    "shared",
                    hash,
                    aDb.sharedClipboardDatabaseQueries
                        .getPayload("shared", hash)
                        .executeAsOne()
                        .encodeToByteArray(),
                )
            }
        }
    }

    private suspend fun fixture(): Fixture {
        getJsonUtils()
        val f = Fixture(clipboardPeerFixture())
        f.aMembers.createClipboard("shared", "a", f.peers.aStore.secureKeyPair.signKeyPair.publicKey, true)
        f.aMembers.approveMember("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey)
        f.bMembers.createClipboard("shared", "b", f.peers.bStore.secureKeyPair.signKeyPair.publicKey, false)
        f.bMembers.approveMember("shared", "a", f.peers.aStore.secureKeyPair.signKeyPair.publicKey)
        return f
    }

    @Test
    fun `legacy first converges into signed native row despite changed wire timestamp and preserves favorite`() =
        runTest {
            val f = fixture()
            val source = f.capture()
            val wire = f.wire(source)
            assertTrue(wire.createTime != source.createTime)
            f.receive(wire)
            val id = f.writes.single().id
            f.bFactory.sqlDriver!!.execute(null, "UPDATE PasteDataEntity SET favorite = 1 WHERE id = $id", 0)
            f.sign()
            f.sync()
            val materialized = f.native.materialize("shared")
            assertEquals(0, materialized.created)
            assertEquals(1, materialized.reused)
            assertEquals(
                id,
                f.bDb.sharedClipboardDatabaseQueries
                    .getProjection("shared", "a", 1)
                    .executeAsOne()
                    .localPasteId,
            )
            assertTrue(rows(f.bDb).single().favorite)
            f.receive(wire)
            assertEquals(listOf(id, id), f.writes.map { it.id })
            assertTrue(f.writes.all { it.captureIdentity == null })
            assertEquals(1, rows(f.bDb).size)
            verify(exactly = 1) { f.taskBuilder.addRenderingTask(id, any()) }
            val identity = assertNotNull(wire.captureIdentity)
            assertEquals(
                1L,
                f.bDb.sharedClipboardDatabaseQueries
                    .getNativeIdentity(
                        identity.originDevice,
                        identity.originPasteId,
                        identity.metadataHash,
                    ).executeAsOne()
                    .authorVerified,
            )
        }

    @Test
    fun `signed history first still allows live automatic clipboard write without another row or render`() =
        runTest {
            val f = fixture()
            val source = f.capture()
            f.sign()
            f.sync()
            assertEquals(1, f.native.materialize("shared").created)
            val id = rows(f.bDb).single().id
            f.receive(f.wire(source))
            assertEquals(id, f.writes.single().id)
            assertEquals(1, rows(f.bDb).size)
            verify(exactly = 0) { f.taskBuilder.addRenderingTask(any(), any()) }
            assertEquals(1, f.native.materialize("shared").reused)
        }

    @Test
    fun `origin identity survives a relay and intentional identical recopy keeps a distinct identity`() =
        runTest {
            val f = fixture()
            repeat(3) { insert(f.bDb, textPaste("placeholder-$it").copy(appInstanceId = "b")) }
            val original = f.capture()
            val firstWire = f.wire(original)
            f.receive(firstWire)
            val local = rows(f.bDb).single { it.id == f.writes.last().id }
            assertTrue(local.id != original.id)
            val relay = f.bIdentities.outgoing(local, "b")
            assertEquals(firstWire.captureIdentity, relay.captureIdentity)
            val cDb = createDatabase(TestDriverFactory())
            val cIdentities = ClipboardNativeIdentityStore(cDb)
            val received = cIdentities.receiveInline(firstWire, { null }, { error("Identity expected") })
            val viaRelay =
                getJsonUtils()
                    .JSON
                    .decodeFromString<PasteData>(
                        getJsonUtils().JSON.encodeToString(relay),
                    ).bindAuthenticatedRemoteIdentity("b")
            assertEquals(received.id, cIdentities.receiveInline(viaRelay, { null }, { error("Identity expected") }).id)
            assertEquals(1, rows(cDb).size)
            val recopy = f.wire(f.capture())
            assertTrue(recopy.captureIdentity != firstWire.captureIdentity)
            assertTrue(cIdentities.receiveInline(recopy, { null }, { error("Identity expected") }).created)
            assertEquals(2, rows(cDb).size)
        }

    @Test
    fun `unverified forged content cannot shadow or become an originally signed capture`() =
        runTest {
            val f = fixture()
            val source = f.capture()
            val real = f.wire(source)
            val wrongItem = createTextPasteItem(text = "forged content")
            val wrong =
                source.copy(
                    pasteAppearItem = wrongItem,
                    size = wrongItem.size,
                    hash = wrongItem.hash,
                    remote = true,
                )
            val forged = assertNotNull(f.bIdentities.verified(wrong, assertNotNull(real.captureIdentity).metadataHash))
            f.receive(wrong.copy(captureIdentity = forged.identity))
            val wrongId = f.writes.single().id
            f.sign()
            f.sync()
            assertEquals(1, f.native.materialize("shared").created)
            val projection =
                f.bDb.sharedClipboardDatabaseQueries
                    .getProjection("shared", "a", 1)
                    .executeAsOne()
            assertTrue(projection.localPasteId != wrongId)
            val native = rows(f.bDb).single { it.id == projection.localPasteId }
            assertEquals("same content", native.getPasteItem(PasteText::class)?.text)
        }

    @Test
    fun `pending and consumed capture export stay identical while edited content drops stale identity`() =
        runTest {
            val f = fixture()
            val source = f.capture()
            val before = f.aIdentities.outgoing(source, "a")
            f.sign()
            val after = f.aIdentities.outgoing(source, "a")
            assertEquals(assertNotNull(before.captureIdentity), after.captureIdentity)
            val changed = createTextPasteItem(text = "edited")
            assertNull(
                f.aIdentities
                    .outgoing(
                        source.copy(pasteAppearItem = changed, size = changed.size, hash = changed.hash),
                        "a",
                    ).captureIdentity,
            )
        }

    @Test
    fun `rich inline flavors converge but a changed alternative cannot reuse the original identity`() =
        runTest {
            val f = fixture()
            val rich = PasteCollection(listOf(createHtmlPasteItem(html = "<b>same content</b>")))
            val source = f.capture(collection = rich)
            val wire = f.wire(source)
            f.receive(wire)
            f.sign()
            f.sync()
            assertEquals(0, f.native.materialize("shared").created)
            assertEquals(1, rows(f.bDb).size)
            var fallback = false
            val altered =
                wire.copy(
                    pasteCollection = PasteCollection(listOf(createHtmlPasteItem(html = "<i>same content</i>"))),
                )
            f.bIdentities.receiveInline(altered, { null }, {
                fallback = true
                99
            })
            assertTrue(fallback)
            assertEquals(1, rows(f.bDb).size)
        }

    @Test
    fun `invalid hints and alternate file representations use the unchanged legacy fallback`() =
        runTest {
            val f = fixture()
            val real = f.wire(f.capture())
            var fallback = 0
            val invalid = real.copy(captureIdentity = real.captureIdentity!!.copy(version = 2))
            assertEquals(
                77,
                f.bIdentities
                    .receiveInline(invalid, { null }, {
                        fallback++
                        77
                    })
                    .id,
            )
            val files =
                createFilesPasteItem(
                    relativePathList = listOf("a.txt"),
                    fileInfoTreeMap =
                        mapOf(
                            "a.txt" to SingleFileInfoTree(1, "file"),
                        ),
                )
            val alternate = real.copy(pasteCollection = PasteCollection(listOf(files)))
            assertEquals(
                78,
                f.bIdentities
                    .receiveInline(alternate, { null }, {
                        fallback++
                        78
                    })
                    .id,
            )
            assertEquals(2, fallback)
            assertTrue(rows(f.bDb).isEmpty())
            assertNull(f.bIdentities.verified(alternate, real.captureIdentity!!.metadataHash))
        }

    @Test
    fun `failed alias insertion rolls back native row and a retry creates exactly one`() =
        runTest {
            val f = fixture()
            val wire = f.wire(f.capture())
            f.bFactory.sqlDriver!!.execute(
                null,
                "CREATE TRIGGER RejectIdentity BEFORE INSERT ON SharedClipboardNativeIdentityEntity BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                0,
            )
            assertFailsWith<Exception> { f.bIdentities.receiveInline(wire, { null }, { error("Identity expected") }) }
            assertTrue(rows(f.bDb).isEmpty())
            f.bFactory.sqlDriver!!.execute(null, "DROP TRIGGER RejectIdentity", 0)
            assertTrue(f.bIdentities.receiveInline(wire, { null }, { error("Identity expected") }).created)
            assertFalse(f.bIdentities.receiveInline(wire, { null }, { error("Identity expected") }).created)
            assertEquals(1, rows(f.bDb).size)
        }

    @Test
    fun `identity migration preserves previous history and membership`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val db = Database(driver, PasteTaskEntity.Adapter(Int2LongAdapter, Int2LongAdapter))
            db.sharedClipboardDatabaseQueries.putWorkspace("existing", true, false)
            db.sharedClipboardDatabaseQueries.insertMember("existing", "saved", "saved-key")
            insert(db, textPaste("saved history"))
            driver.execute(null, "DROP TABLE SharedClipboardNativeIdentityEntity", 0)
            driver.execute(null, "DROP INDEX SharedClipboardCapturePaste", 0)
            assertEquals(15L, Database.Schema.version)
            Database.Schema.migrate(driver, 14, 15)
            val plan =
                driver
                    .executeQuery(
                        null,
                        "EXPLAIN QUERY PLAN SELECT * FROM SharedClipboardCaptureEntity WHERE localPasteId = 1",
                        { cursor ->
                            cursor.next()
                            QueryResult.Value(cursor.getString(3))
                        },
                        0,
                    ).value
            assertTrue(assertNotNull(plan).contains("SEARCH"), plan)
            assertEquals("saved history", rows(db).single().getPasteItem(PasteText::class)?.text)
            assertEquals(
                "saved-key",
                db.sharedClipboardDatabaseQueries
                    .getMember("existing", "saved")
                    .executeAsOne()
                    .signingPublicKey,
            )
        } finally {
            driver.close()
        }
    }

    companion object {
        private fun textPaste(text: String): PasteData {
            val item = createTextPasteItem(text = text)
            return PasteData(
                appInstanceId = "a",
                pasteAppearItem = item,
                pasteCollection = PasteCollection(emptyList()),
                pasteType = 0,
                source = "Editor",
                size = item.size,
                hash = item.hash,
                createTime = 10,
                pasteState = PasteState.LOADED,
            )
        }

        private fun insert(
            db: Database,
            paste: PasteData,
        ): Long {
            db.pasteDatabaseQueries.createPasteDataEntity(
                paste.appInstanceId,
                paste.favorite,
                paste.pasteAppearItem?.toStoredJson(),
                paste.pasteCollection.toStoredJson(),
                paste.pasteType.toLong(),
                paste.source,
                paste.size,
                paste.hash,
                paste.createTime,
                null,
                PasteState.LOADED.toLong(),
                paste.remote,
            )
            return db.pasteDatabaseQueries.getLastId().executeAsOne()
        }

        private fun rows(db: Database): List<PasteData> =
            db.pasteDatabaseQueries.getPasteDataListLimit(100, PasteData::mapper).executeAsList()
    }
}
