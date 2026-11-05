package com.crosspaste.sync.journal

import com.crosspaste.Database
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class SqlClipboardSelectionStore(
    private val database: Database,
    private val clipboard: String,
    private val authenticator: ClipboardSelectionAuthenticator,
) {
    suspend fun ingest(incoming: List<SignedClipboardSelection>) {
        incoming.forEach { authenticator.verify(it) }
        val queries = database.sharedClipboardDatabaseQueries
        database.transaction {
            require(queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled == true)
            incoming.forEach { signed ->
                val selection = signed.selection
                require(selection.clipboard == clipboard)
                val existing =
                    queries
                        .getClipboardSelection(clipboard, selection.author, selection.clock)
                        .executeAsOneOrNull()
                if (existing == null) {
                    queries.insertClipboardSelection(
                        clipboard,
                        selection.author,
                        selection.clock,
                        Json.encodeToString(signed),
                    )
                } else {
                    require(Json.decodeFromString<SignedClipboardSelection>(existing).selection == selection) {
                        "Conflicting selection identity"
                    }
                }
            }
        }
    }

    suspend fun latest(): SignedClipboardSelection? =
        database.sharedClipboardDatabaseQueries
            .getLatestClipboardSelection(clipboard)
            .executeAsOneOrNull()
            ?.let {
                Json.decodeFromString<SignedClipboardSelection>(it).also { signed ->
                    require(signed.selection.clipboard == clipboard)
                    authenticator.verify(signed)
                }
            }

    suspend fun load(): List<SignedClipboardSelection> =
        database.sharedClipboardDatabaseQueries
            .listClipboardSelections(clipboard)
            .executeAsList()
            .map {
                Json.decodeFromString<SignedClipboardSelection>(it).also { signed ->
                    require(signed.selection.clipboard == clipboard)
                    authenticator.verify(signed)
                }
            }
}
