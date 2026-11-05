package com.crosspaste.paste

import com.crosspaste.app.AppInfo
import com.crosspaste.config.AppConfig
import com.crosspaste.config.CommonConfigManager
import com.crosspaste.db.TestDriverFactory
import com.crosspaste.db.createDatabase
import com.crosspaste.db.paste.SqlPasteDao
import com.crosspaste.paste.item.CreatePasteItemHelper.createImagesPasteItem
import com.crosspaste.paste.item.CreatePasteItemHelper.createTextPasteItem
import com.crosspaste.paste.item.DefaultPasteItemReader
import com.crosspaste.paste.item.ImagesPasteItem
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.paste.item.getFilePaths
import com.crosspaste.path.PlatformUserDataPathProvider
import com.crosspaste.path.UserDataPathProvider
import com.crosspaste.presist.SingleFileInfoTree
import com.crosspaste.sync.journal.DesktopClipboardRecordingPolicy
import com.crosspaste.task.TaskBuilder
import com.crosspaste.task.TaskSubmitter
import com.crosspaste.utils.DateUtils
import com.crosspaste.utils.getJsonUtils
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasteReleaseServiceImageDedupTest {

    // Eagerly initialize JsonUtils to avoid circular class initialization between
    // PasteItem.Companion (which calls getJsonUtils()) and paste item classes
    @Suppress("unused")
    private val jsonUtils = getJsonUtils()

    private val appInfo =
        AppInfo(
            appInstanceId = "test-instance",
            appVersion = "1.0.0",
            appRevision = "abc",
            userName = "testUser",
        )

    private val searchContentService =
        object : SearchContentService {
            override fun createSearchContent(
                source: String?,
                searchContentList: List<String>,
            ): String =
                (
                    listOfNotNull(source?.lowercase()) +
                        searchContentList.map { it.lowercase() }
                ).joinToString(" ")

            override fun createSearchTerms(queryString: String): List<String> =
                queryString.trim().split("\\s+".toRegex()).filter {
                    it.isNotBlank()
                }
        }

    // Thread-safe: the concurrent-release test records from parallel coroutines.
    private class RecordingTaskBuilder : TaskBuilder {
        val deleteIds = CopyOnWriteArrayList<Long>()
        val syncTaskCount = AtomicInteger(0)
        val renderingTaskCount = AtomicInteger(0)

        override fun addDelayedDeletePasteTask(
            id: Long,
            delayMillis: Long,
        ): TaskBuilder = this

        override fun addDeletePasteTasks(ids: List<Long>): TaskBuilder {
            deleteIds.addAll(ids)
            return this
        }

        override fun addPullFileTask(
            id: Long,
            remotePasteDataId: Long,
        ): TaskBuilder = this

        override fun addSyncTask(
            id: Long,
            fileSize: Long,
            appInstanceId: String,
            targetAppInstanceIds: Set<String>?,
        ): TaskBuilder {
            syncTaskCount.incrementAndGet()
            return this
        }

        override fun addRelaySyncTask(
            id: Long,
            appInstanceId: String,
        ): TaskBuilder = this

        override fun addPullIconTask(
            id: Long,
            existIconFile: Boolean,
        ): TaskBuilder = this

        override fun addRenderingTask(
            id: Long,
            pasteType: PasteType,
        ): TaskBuilder {
            renderingTaskCount.incrementAndGet()
            return this
        }
    }

    private class ImmediateTaskSubmitter : TaskSubmitter {
        val builder = RecordingTaskBuilder()

        override suspend fun submit(block: suspend TaskBuilder.() -> Unit) {
            builder.block()
        }
    }

    private class Fixture {
        val taskSubmitter = ImmediateTaskSubmitter()
        val currentPaste = mockk<CurrentPaste>(relaxed = true)
        val driverFactory = TestDriverFactory()
        val database = createDatabase(driverFactory)
        val configManager = mockk<CommonConfigManager>(relaxed = true)
        private val pasteItemReader = DefaultPasteItemReader()

        lateinit var pasteDao: SqlPasteDao
        lateinit var service: PasteReleaseService
        var refreshRecording: () -> Unit = {}

        fun init(
            appInfo: AppInfo,
            searchContentService: SearchContentService,
            userDataPathProvider: UserDataPathProvider,
        ) {
            every { configManager.getCurrentConfig().enableSyncText } returns true
            pasteDao =
                SqlPasteDao(
                    appInfo = appInfo,
                    database = database,
                    pasteItemReader = pasteItemReader,
                    searchContentService = searchContentService,
                    taskSubmitter = taskSubmitter,
                    userDataPathProvider = userDataPathProvider,
                )
            service =
                PasteReleaseService(
                    commonConfigManager = configManager,
                    currentPaste = currentPaste,
                    database = database,
                    notificationManager = mockk(relaxed = true),
                    pasteDao = pasteDao,
                    pasteItemReader = pasteItemReader,
                    pasteProcessPlugins = emptyList(),
                    pastePullCursorManager = mockk(relaxed = true),
                    searchContentService = searchContentService,
                    syncRuntimeInfoDao = mockk(relaxed = true),
                    taskSubmitter = taskSubmitter,
                    userDataPathProvider = userDataPathProvider,
                    refreshSharedRecordingPolicy = { refreshRecording() },
                )
        }
    }

    private fun newFixture(userDataPathProvider: UserDataPathProvider = mockk(relaxed = true)): Fixture =
        Fixture().apply { init(appInfo, searchContentService, userDataPathProvider) }

    @Test
    fun `desktop recording off on off on captures immediately without changing automatic paste or paused clipboards`() =
        runTest {
            val fixture = newFixture()
            var recording = false
            every { fixture.configManager.getCurrentConfig().enablePasteboardListening } answers { recording }
            every { fixture.configManager.getCurrentConfig().useSharedClipboardForPaste } returns false
            val queries = fixture.database.sharedClipboardDatabaseQueries
            queries.putWorkspace("shared", true, false)
            queries.putWorkspace("paused", false, false)
            val policy = DesktopClipboardRecordingPolicy(fixture.database) { recording }
            fixture.refreshRecording = policy::refresh

            suspend fun copy(text: String) {
                val id = fixture.createLoadingRecord()
                fixture.service.releaseLocalPasteData(id, listOf(createTextPasteItem(text = text)), null)
                assertEquals(PasteState.LOADED, fixture.pasteDao.getNoDeletePasteDataBlock(id)?.pasteState)
            }
            copy("off")
            assertTrue(queries.getPendingCaptures("shared", 50).executeAsList().isEmpty())
            recording = true
            copy("first copy immediately after on")
            assertEquals(1, queries.getPendingCaptures("shared", 50).executeAsList().size)
            recording = false
            policy.refresh()
            copy("off again")
            assertEquals(1, queries.getPendingCaptures("shared", 50).executeAsList().size)
            recording = true
            copy("on again")
            assertEquals(2, queries.getPendingCaptures("shared", 50).executeAsList().size)
            assertEquals(false, fixture.configManager.getCurrentConfig().useSharedClipboardForPaste)
            assertEquals(false, queries.getWorkspace("paused").executeAsOne().enabled)
            assertEquals(false, queries.getWorkspace("paused").executeAsOne().contributeLocal)
            assertTrue(queries.getPendingCaptures("paused", 50).executeAsList().isEmpty())
        }

    @Test
    fun `shared managed bytes survive local cleanup and capture acknowledgement until all references release`(
        @TempDir tempDir: File,
    ) = runTest {
        val storage = File(tempDir, "storage")
        val fixture = newFixture(userDataPathProvider(storage))
        val file =
            File(storage, "images/img/a.png").also {
                it.parentFile.mkdirs()
                it.writeText("original bytes")
            }
        val id = fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, DateUtils.nowEpochMilliseconds())
        val queries = fixture.database.sharedClipboardDatabaseQueries
        queries.putWorkspace("one", true, true)
        queries.putWorkspace("two", true, true)
        queries.enqueueLoadedLocalCapture(id)
        fixture.database.pasteDatabaseQueries.markDeletePasteData(listOf(id))
        fixture.pasteDao.deletePasteData(id)
        assertEquals("original bytes", file.readText())
        assertNotNull(fixture.pasteDao.getDeletePasteData(id))
        assertNull(fixture.pasteDao.getLoadedPasteDataBlock(id))
        queries.completeCapture("one", id)
        queries.completeCapture("two", id)
        fixture.pasteDao.deletePasteData(id)
        assertEquals("original bytes", file.readText())
        queries.releaseRetention("one", id)
        fixture.pasteDao.deletePasteData(id)
        assertTrue(file.exists())
        queries.releaseRetention("two", id)
        fixture.pasteDao.deletePasteData(id)
        assertTrue(!file.exists())
        assertNull(fixture.pasteDao.getDeletePasteData(id))
    }

    @Test
    fun `unjoined managed files keep their original cleanup behavior`(
        @TempDir tempDir: File,
    ) = runTest {
        val storage = File(tempDir, "storage")
        val fixture = newFixture(userDataPathProvider(storage))
        val file =
            File(storage, "images/img/a.png").also {
                it.parentFile.mkdirs()
                it.writeText("ordinary bytes")
            }
        val id = fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, DateUtils.nowEpochMilliseconds())
        fixture.database.pasteDatabaseQueries.markDeletePasteData(listOf(id))
        fixture.pasteDao.deletePasteData(id)
        assertTrue(!file.exists())
        assertNull(fixture.pasteDao.getDeletePasteData(id))
    }

    @Test
    fun `joined clipboard captures completed local content durably before history cleanup`() =
        runTest {
            val fixture = newFixture()
            every { fixture.configManager.getCurrentConfig().enablePasteboardListening } returns true
            val queries = fixture.database.sharedClipboardDatabaseQueries
            queries.putWorkspace("shared", true, true)
            val id = fixture.createLoadingRecord()
            fixture.service.releaseLocalPasteData(
                id,
                listOf(createTextPasteItem(text = "original clipboard text")),
                null,
            )
            val capture = queries.getPendingCaptures("shared", 50).executeAsOne()
            assertEquals(id, capture.localPasteId)
            assertEquals(appInfo.appInstanceId, capture.originDevice)
            assertTrue(capture.pasteAppearItem.contains("original clipboard text"))
            fixture.pasteDao.markDeletePasteData(id)
            assertEquals(capture, queries.getPendingCaptures("shared", 50).executeAsOne())
        }

    @Test
    fun `capture respects contribution pause workspace membership and explicit recipient scope`() =
        runTest {
            for (mode in listOf("unjoined", "disabled", "contribution-off", "paused", "targeted", "type-disabled")) {
                val fixture = newFixture()
                every { fixture.configManager.getCurrentConfig().enablePasteboardListening } returns (mode != "paused")
                every { fixture.configManager.getCurrentConfig().enableSyncText } returns (mode != "type-disabled")
                val queries = fixture.database.sharedClipboardDatabaseQueries
                if (mode != "unjoined") queries.putWorkspace("shared", mode != "disabled", mode != "contribution-off")
                val id = fixture.createLoadingRecord()
                fixture.service.releaseLocalPasteData(
                    id,
                    listOf(createTextPasteItem(text = "private-$mode")),
                    if (mode ==
                        "targeted"
                    ) {
                        setOf("selected-peer")
                    } else {
                        null
                    },
                )
                assertTrue(queries.getPendingCaptures("shared", 50).executeAsList().isEmpty(), mode)
                assertEquals(PasteState.LOADED, fixture.pasteDao.getNoDeletePasteDataBlock(id)?.pasteState)
            }
        }

    @Test
    fun `discarded duplicate images never produce a shared capture`() =
        runTest {
            val fixture = newFixture()
            every { fixture.configManager.getCurrentConfig().enablePasteboardListening } returns true
            fixture.database.sharedClipboardDatabaseQueries.putWorkspace("shared", true, true)
            fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, DateUtils.nowEpochMilliseconds())
            val id = fixture.createLoadingRecord()
            fixture.service.releaseLocalPasteData(id, listOf(imageItem()), null)
            assertNotNull(fixture.pasteDao.getDeletePasteData(id))
            assertTrue(
                fixture.database.sharedClipboardDatabaseQueries
                    .getPendingCaptures("shared", 50)
                    .executeAsList()
                    .isEmpty(),
            )
        }

    @Test
    fun `capture insertion failure rolls back the loaded row in the same transaction`() =
        runTest {
            val fixture = newFixture()
            every { fixture.configManager.getCurrentConfig().enablePasteboardListening } returns true
            fixture.database.sharedClipboardDatabaseQueries.putWorkspace("shared", true, true)
            fixture.driverFactory.sqlDriver!!.execute(
                null,
                "CREATE TRIGGER RejectCapture BEFORE INSERT ON SharedClipboardCaptureEntity " +
                    "BEGIN SELECT RAISE(ABORT, 'test capture storage failure'); END",
                0,
            )
            val id = fixture.createLoadingRecord()
            assertFails {
                fixture.service.releaseLocalPasteData(
                    id,
                    listOf(createTextPasteItem(text = "rollback")),
                    null,
                )
            }
            assertNotNull(fixture.pasteDao.getLoadingPasteData(id))
            assertTrue(
                fixture.database.sharedClipboardDatabaseQueries
                    .getPendingCaptures("shared", 50)
                    .executeAsList()
                    .isEmpty(),
            )
        }

    private fun userDataPathProvider(storageDir: File): UserDataPathProvider {
        val appConfig = mockk<AppConfig>()
        every { appConfig.useDefaultStoragePath } returns true
        val configManager = mockk<CommonConfigManager>()
        every { configManager.getCurrentConfig() } returns appConfig
        val platformProvider = mockk<PlatformUserDataPathProvider>()
        every { platformProvider.getUserDefaultStoragePath() } returns storageDir.toOkioPath()
        return UserDataPathProvider(configManager, platformProvider)
    }

    private fun refImageItem(basePath: File): ImagesPasteItem =
        createImagesPasteItem(
            identifiers = listOf("image/png"),
            basePath = basePath.absolutePath,
            relativePathList = listOf("a.png"),
            fileInfoTreeMap = mapOf("a.png" to SingleFileInfoTree(size = 10L, hash = "file-hash")),
        )

    private fun imageItem(fileHash: String = "file-hash"): ImagesPasteItem =
        createImagesPasteItem(
            identifiers = listOf("image/png"),
            relativePathList = listOf("img/a.png"),
            fileInfoTreeMap = mapOf("a.png" to SingleFileInfoTree(size = 10L, hash = fileHash)),
        )

    private suspend fun Fixture.createLoadedRecord(
        item: PasteItem,
        pasteType: PasteType,
        createTime: Long,
        remote: Boolean = false,
    ): Long =
        pasteDao.createPasteData(
            PasteData(
                appInstanceId = if (remote) "remote-device" else appInfo.appInstanceId,
                pasteAppearItem = item,
                pasteCollection = PasteCollection(emptyList()),
                pasteType = pasteType.type,
                size = item.size,
                hash = item.hash,
                createTime = createTime,
                pasteState = PasteState.LOADED,
                remote = remote,
            ),
        )

    private suspend fun Fixture.createLoadingRecord(createTime: Long = DateUtils.nowEpochMilliseconds()): Long =
        pasteDao.createPasteData(
            PasteData(
                appInstanceId = appInfo.appInstanceId,
                pasteCollection = PasteCollection(emptyList()),
                pasteType = PasteType.INVALID_TYPE.type,
                size = 0L,
                hash = "",
                createTime = createTime,
                pasteState = PasteState.LOADING,
            ),
        )

    @Test
    fun `duplicate local image within window is discarded keeping first record`() =
        runTest {
            val fixture = newFixture()
            val item = imageItem()
            val keptId =
                fixture.createLoadedRecord(item, PasteType.IMAGE_TYPE, DateUtils.nowEpochMilliseconds())
            val loadingId = fixture.createLoadingRecord()

            fixture.service.releaseLocalPasteData(loadingId, listOf(imageItem()), null)

            // Second record is marked deleted with the final item persisted, so the
            // delete task can reclaim the just-written image files.
            val discarded = fixture.pasteDao.getDeletePasteData(loadingId)
            assertNotNull(discarded)
            val discardedItem = discarded.pasteAppearItem as ImagesPasteItem
            assertContentEquals(listOf("img/a.png"), discardedItem.relativePathList)
            assertEquals(item.hash, discarded.hash)

            // Only a delete task for the discarded record — never sync or rendering.
            assertContentEquals(listOf(loadingId), fixture.taskSubmitter.builder.deleteIds)
            assertEquals(
                0,
                fixture.taskSubmitter.builder.syncTaskCount
                    .get(),
            )
            assertEquals(
                0,
                fixture.taskSubmitter.builder.renderingTaskCount
                    .get(),
            )
            coVerify(exactly = 0) { fixture.currentPaste.setPasteId(any()) }

            // First record is untouched.
            assertNotNull(fixture.pasteDao.getNoDeletePasteDataBlock(keptId))
        }

    @Test
    fun `identical image outside dedup window is kept`() =
        runTest {
            val fixture = newFixture()
            val outsideWindow =
                DateUtils.nowEpochMilliseconds() -
                    PasteReleaseService.IMAGE_DEDUP_WINDOW.inWholeMilliseconds - 1000L
            fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, outsideWindow)
            val loadingId = fixture.createLoadingRecord()

            fixture.service.releaseLocalPasteData(loadingId, listOf(imageItem()), null)

            val released = fixture.pasteDao.getNoDeletePasteDataBlock(loadingId)
            assertNotNull(released)
            assertEquals(PasteState.LOADED, released.pasteState)
            assertTrue(
                fixture.taskSubmitter.builder.deleteIds
                    .isEmpty(),
            )
            assertEquals(
                1,
                fixture.taskSubmitter.builder.syncTaskCount
                    .get(),
            )
            assertEquals(
                1,
                fixture.taskSubmitter.builder.renderingTaskCount
                    .get(),
            )
        }

    @Test
    fun `remote record with same hash does not trigger dedup`() =
        runTest {
            val fixture = newFixture()
            fixture.createLoadedRecord(
                imageItem(),
                PasteType.IMAGE_TYPE,
                DateUtils.nowEpochMilliseconds(),
                remote = true,
            )
            val loadingId = fixture.createLoadingRecord()

            fixture.service.releaseLocalPasteData(loadingId, listOf(imageItem()), null)

            val released = fixture.pasteDao.getNoDeletePasteDataBlock(loadingId)
            assertNotNull(released)
            assertEquals(PasteState.LOADED, released.pasteState)
            assertTrue(
                fixture.taskSubmitter.builder.deleteIds
                    .isEmpty(),
            )
        }

    @Test
    fun `text pastes keep existing same-hash cleanup semantics`() =
        runTest {
            val fixture = newFixture()
            val textItem = createTextPasteItem(text = "hello")
            val oldId =
                fixture.createLoadedRecord(textItem, PasteType.TEXT_TYPE, DateUtils.nowEpochMilliseconds())
            val loadingId = fixture.createLoadingRecord()

            fixture.service.releaseLocalPasteData(loadingId, listOf(createTextPasteItem(text = "hello")), null)

            // Existing behavior: the new record is kept and the old same-hash
            // record is removed (keep-new), untouched by image keep-first dedup.
            val released = fixture.pasteDao.getNoDeletePasteDataBlock(loadingId)
            assertNotNull(released)
            assertEquals(PasteState.LOADED, released.pasteState)
            assertContentEquals(listOf(oldId), fixture.taskSubmitter.builder.deleteIds)
        }

    @Test
    fun `createLocalPasteData stores a new paste through the local release path`() =
        runTest {
            val fixture = newFixture()

            val id =
                fixture.service.createLocalPasteData(
                    appInstanceId = appInfo.appInstanceId,
                    pasteItem = createTextPasteItem(text = "from a plugin"),
                    source = "com.example.plugin",
                )

            val released = fixture.pasteDao.getNoDeletePasteDataBlock(id)
            assertNotNull(released)
            assertEquals(PasteState.LOADED, released.pasteState)
            assertEquals(PasteType.TEXT_TYPE.type, released.pasteType)
            assertEquals("com.example.plugin", released.source)
            assertEquals(
                1,
                fixture.taskSubmitter.builder.syncTaskCount
                    .get(),
            )
        }

    @Test
    fun `dedup query ignores deleted records`() =
        runTest {
            val fixture = newFixture()
            val item = imageItem()
            fixture.pasteDao.createPasteData(
                PasteData(
                    appInstanceId = appInfo.appInstanceId,
                    pasteAppearItem = item,
                    pasteCollection = PasteCollection(emptyList()),
                    pasteType = PasteType.IMAGE_TYPE.type,
                    size = item.size,
                    hash = item.hash,
                    pasteState = PasteState.DELETED,
                ),
            )

            assertNull(
                fixture.pasteDao.getRecentSameHashLocalPasteId(
                    item.hash,
                    PasteType.IMAGE_TYPE.type,
                    0L,
                    Long.MAX_VALUE,
                    -1L,
                ),
            )
        }

    @Test
    fun `loading row with same hash is not a kept candidate`() =
        runTest {
            val fixture = newFixture()
            val item = imageItem()
            // A LOADING row that (contrary to the current convention of an empty
            // placeholder hash) already carries the final hash must still not be
            // treated as a kept candidate: it is not fully released yet.
            fixture.pasteDao.createPasteData(
                PasteData(
                    appInstanceId = appInfo.appInstanceId,
                    pasteAppearItem = item,
                    pasteCollection = PasteCollection(emptyList()),
                    pasteType = PasteType.IMAGE_TYPE.type,
                    size = item.size,
                    hash = item.hash,
                    pasteState = PasteState.LOADING,
                ),
            )

            assertNull(
                fixture.pasteDao.getRecentSameHashLocalPasteId(
                    item.hash,
                    PasteType.IMAGE_TYPE.type,
                    0L,
                    Long.MAX_VALUE,
                    -1L,
                ),
            )
        }

    @Test
    fun `dedup window compares record create times not release time`() =
        runTest {
            val fixture = newFixture()
            val now = DateUtils.nowEpochMilliseconds()
            // Both records were created ~8s ago, only 50ms apart — slow image
            // processing delayed the release, but they are still duplicates of a
            // single clipboard operation and must be deduped.
            fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, now - 8000L)
            val loadingId = fixture.createLoadingRecord(createTime = now - 7950L)

            fixture.service.releaseLocalPasteData(loadingId, listOf(imageItem()), null)

            assertNotNull(fixture.pasteDao.getDeletePasteData(loadingId))
            assertContentEquals(listOf(loadingId), fixture.taskSubmitter.builder.deleteIds)
        }

    @Test
    fun `loaded candidate newer than the window does not trigger dedup`() =
        runTest {
            val fixture = newFixture()
            val now = DateUtils.nowEpochMilliseconds()
            // The same image was copied again 10s after this record's event and
            // finished releasing first (out of order); the delayed old record is a
            // separate operation and must not discard itself against it.
            fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, now)
            val loadingId = fixture.createLoadingRecord(createTime = now - 10_000L)

            fixture.service.releaseLocalPasteData(loadingId, listOf(imageItem()), null)

            val released = fixture.pasteDao.getNoDeletePasteDataBlock(loadingId)
            assertNotNull(released)
            assertEquals(PasteState.LOADED, released.pasteState)
            assertTrue(
                fixture.taskSubmitter.builder.deleteIds
                    .isEmpty(),
            )
        }

    @Test
    fun `concurrent duplicate releases keep exactly one record`() =
        runTest {
            val fixture = newFixture()
            val firstId = fixture.createLoadingRecord()
            val secondId = fixture.createLoadingRecord()

            listOf(firstId, secondId)
                .map { id ->
                    async(Dispatchers.Default) {
                        fixture.service.releaseLocalPasteData(id, listOf(imageItem()), null)
                    }
                }.awaitAll()

            val loadedIds =
                listOf(firstId, secondId).filter {
                    fixture.pasteDao.getNoDeletePasteDataBlock(it) != null
                }
            assertEquals(1, loadedIds.size)
            val discardedId = (listOf(firstId, secondId) - loadedIds.toSet()).single()
            assertNotNull(fixture.pasteDao.getDeletePasteData(discardedId))
            assertContentEquals(listOf(discardedId), fixture.taskSubmitter.builder.deleteIds)
        }

    @Test
    fun `ref item pointing into managed storage does not mark the original deleted`() =
        runTest {
            val tempDir = Files.createTempDirectory("ref-into-storage").toFile()
            tempDir.deleteOnExit()
            val storageDir = File(tempDir, "storage")
            val fixture = newFixture(userDataPathProvider(storageDir))
            // The original record owns storage/images/img/a.png; copying that file
            // from the file manager yields a ref item whose basePath is its folder.
            val ownedFile = File(storageDir, "images/img/a.png").also { it.parentFile.mkdirs() }
            ownedFile.writeText("png")
            val originalId =
                fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, DateUtils.nowEpochMilliseconds())
            val loadingId = fixture.createLoadingRecord()

            fixture.service.releaseLocalPasteData(loadingId, listOf(refImageItem(ownedFile.parentFile)), null)

            assertTrue(
                fixture.taskSubmitter.builder.deleteIds
                    .isEmpty(),
            )
            assertNotNull(fixture.pasteDao.getNoDeletePasteDataBlock(originalId))
            assertNotNull(fixture.pasteDao.getNoDeletePasteDataBlock(loadingId))
            assertTrue(ownedFile.exists())
        }

    @Test
    fun `ref item outside managed storage still removes same-hash records`() =
        runTest {
            val tempDir = Files.createTempDirectory("ref-outside-storage").toFile()
            tempDir.deleteOnExit()
            val fixture = newFixture(userDataPathProvider(File(tempDir, "storage")))
            val downloads = File(tempDir, "Downloads").also { it.mkdirs() }
            File(downloads, "a.png").writeText("png")
            val originalId =
                fixture.createLoadedRecord(imageItem(), PasteType.IMAGE_TYPE, DateUtils.nowEpochMilliseconds())
            val loadingId = fixture.createLoadingRecord()

            fixture.service.releaseLocalPasteData(loadingId, listOf(refImageItem(downloads)), null)

            assertContentEquals(listOf(originalId), fixture.taskSubmitter.builder.deleteIds)
        }

    @Test
    fun `files collected for a row deleted mid-collect are reclaimed`() =
        runTest {
            val storageDir = Files.createTempDirectory("crosspaste-collect-test").toFile()
            val provider = userDataPathProvider(storageDir)
            val fixture = newFixture(provider)
            val id = fixture.createLoadingRecord()
            fixture.pasteDao.markDeletePasteData(id)
            val item = imageItem()
            val copied =
                item.getFilePaths(provider).single().toFile().also {
                    it.parentFile.mkdirs()
                    it.writeText("copied")
                }

            fixture.service.releaseLocalPasteData(id, listOf(item), null)

            assertTrue(!copied.exists(), "the copy made after the delete must not be orphaned")
        }
}
