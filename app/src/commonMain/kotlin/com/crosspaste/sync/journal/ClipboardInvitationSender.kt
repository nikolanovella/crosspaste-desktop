package com.crosspaste.sync.journal

import kotlinx.serialization.json.Json

/** Sends invitations for an explicitly created clipboard; never grants consent on the recipient. */
class ClipboardInvitationSender(
    private val client: ClipboardPeerClient,
    private val invitations: ClipboardInvitationService,
) {
    suspend fun send(
        clipboard: String,
        peer: String,
        prepared: (ClipboardInvitation) -> Unit = {},
    ): ClipboardInvitation {
        val identity = client.identity(peer, clipboard)
        val offer = invitations.prepare(clipboard, identity)
        prepared(offer)
        val reply = client.propose(offer)
        val status = Json.decodeFromString<ClipboardInvitationStatus>(reply.payload)
        require(status.id == offer.id && status.clipboard == offer.clipboard) {
            "Invitation acknowledgement belongs to another clipboard"
        }
        // Even an accepted acknowledgement is confirmed through the status operation,
        // which binds the exact invitation and its authenticated identity.
        return offer
    }

    suspend fun check(offer: ClipboardInvitation): ClipboardInvitationState =
        invitations.confirm(client.status(offer.recipient, offer.clipboard, offer.id))
}
