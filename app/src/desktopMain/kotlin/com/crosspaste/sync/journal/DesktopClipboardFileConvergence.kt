package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.paste.ClipboardCaptureIdentity
import com.crosspaste.paste.PasteCollection
import com.crosspaste.paste.PasteData
import com.crosspaste.paste.PasteState
import com.crosspaste.paste.item.PasteItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The existing legacy transfer completes normally. Only private verified bytes can replace its row. */
internal class DesktopClipboardFileConvergence(
    private val database: Database,
    private val history: ClipboardHistoryService,
    private val identities: ClipboardNativeIdentityStore,
    private val landing: DesktopClipboardFileLanding,
    private val localDevice: String,
    private val search: (PasteData) -> String?,
) {
    suspend fun complete(paste: PasteData): PasteData? = identities.completeFile(paste, ::verifiedLanding, search)

    suspend fun receiveInline(
        paste: PasteData,
        fallback: suspend () -> Long,
    ): ClipboardNativeIdentityStore.Received = identities.receiveInline(paste, search, fallback, ::verifiedLanding)

    private suspend fun verifiedLanding(
        identity: ClipboardCaptureIdentity,
        peer: String,
    ): ClipboardNativeIdentityStore.VerifiedFileLanding? {
        val queries = database.sharedClipboardDatabaseQueries
        val entry = Json.encodeToString(listOf("capture/v1", identity.originDevice, identity.originPasteId.toString()))
        for (workspace in queries.getEnabledWorkspaces().executeAsList()) {
            val sequences =
                queries
                    .getCaptureEventSequences(
                        workspace.clipboardId,
                        identity.originDevice,
                        entry,
                    ).executeAsList()
            for (sequence in sequences) {
                try {
                    // This checks the authenticated legacy sender's existing membership and
                    // the active original signed event; a hint alone gives neither access nor bytes.
                    val context =
                        history.receivingFileContext(
                            peer,
                            workspace.clipboardId,
                            ClipboardEventId(identity.originDevice, sequence),
                        )
                    if (context.event.change.contentHash != identity.metadataHash) continue
                    val capture =
                        Json.decodeFromString<CapturedClipboardPayload>(
                            context.metadata.decodeToString(throwOnInvalidSequence = true),
                        )
                    require(capture.size >= 0 && capture.pasteType in 0..Int.MAX_VALUE.toLong())
                    val original =
                        PasteData(
                            id = capture.localPasteId,
                            appInstanceId = capture.originDevice,
                            pasteAppearItem = PasteItem.fromStoredJson(capture.pasteAppearItem),
                            pasteCollection = PasteCollection.fromStoredJson(capture.pasteCollection),
                            pasteType = capture.pasteType.toInt(),
                            source = capture.source,
                            size = capture.size,
                            hash = capture.originalHash,
                            createTime = capture.createTime,
                            pasteState = PasteState.LOADED,
                            remote = capture.originDevice != localDevice,
                        )
                    if (identities.verified(original, identity.metadataHash)?.identity != identity) continue
                    val ready = landing.land(context.event, context.metadata, original)
                    if (ready !=
                        null
                    ) {
                        return ClipboardNativeIdentityStore.VerifiedFileLanding(
                            workspace.clipboardId,
                            context.event.change.id,
                            ready,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Unavailable, edited or corrupt private bytes must not retire a valid legacy copy.
                }
            }
        }
        return null
    }
}
