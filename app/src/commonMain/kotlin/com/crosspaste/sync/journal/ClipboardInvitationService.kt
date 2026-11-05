package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.net.routing.ClipboardPeerIdentity
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.secure.SecureStore
import com.crosspaste.utils.DateUtils.nowEpochMilliseconds
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ClipboardMemberKey(
    val deviceId: String,
    val signingPublicKey: String,
)

@Serializable
data class ClipboardInvitation(
    val id: String,
    val clipboard: String,
    val inviter: String,
    val recipient: String,
    val members: List<ClipboardMemberKey>,
    val admissionProposal: ClipboardAdmissionProposal? = null,
)

@Serializable
enum class ClipboardInvitationState { PENDING, ACCEPTED, DECLINED, EXPIRED }

@Serializable
data class ClipboardInvitationLookup(
    val id: String,
)

@Serializable
data class ClipboardInvitationStatus(
    val id: String,
    val clipboard: String,
    val state: ClipboardInvitationState,
    val signingPublicKey: String? = null,
    val admission: ClipboardAdmission? = null,
)

/** Incoming network requests can propose and query only. Acceptance is a separate local UI action. */
class ClipboardInvitationService(
    private val database: Database,
    private val localDevice: String,
    private val secureStore: SecureStore,
    private val serializer: SecureKeyPairSerializer,
    private val now: () -> Long = ::nowEpochMilliseconds,
) {
    private data class Pending(
        val offer: ClipboardInvitation,
        val created: Long,
        var status: ClipboardInvitationStatus,
    )

    private data class Outgoing(
        val offer: ClipboardInvitation,
        val expectedKey: String,
        val created: Long,
        var state: ClipboardInvitationState = ClipboardInvitationState.PENDING,
    )

    private val members = ClipboardMembershipStore(database, serializer)
    private val admissions = ClipboardAdmissionStore(database, serializer)
    private val codecs = getCodecsUtils()
    private val mutex = Mutex()
    private val incoming = mutableMapOf<String, Pending>()
    private val outgoing = mutableMapOf<String, Outgoing>()
    private val incomingState = MutableStateFlow<List<ClipboardInvitation>>(emptyList())
    val invitations = incomingState.asStateFlow()

    suspend fun prepare(
        clipboard: String,
        identityReply: VerifiedClipboardPeerReply,
    ): ClipboardInvitation =
        mutex.withLock {
            expire()
            require(
                identityReply.request.operation == ClipboardPeerOperation.IDENTITY &&
                    identityReply.request.clipboard == clipboard,
            )
            require(identityReply.request.sender == localDevice && identityReply.peer != localDevice)
            require(secureStore.existCryptPublicKey(identityReply.peer)) { "Device is no longer paired" }
            require(
                database.sharedClipboardDatabaseQueries
                    .getWorkspace(clipboard)
                    .executeAsOneOrNull()
                    ?.enabled == true,
            )
            val identity = Json.decodeFromString<ClipboardPeerIdentity>(identityReply.payload)
            require(identity.deviceId == identityReply.peer && identity.protocolVersion == 1)
            val key = canonicalKey(identity.signingPublicKey)
            val known = members.memberKeys(clipboard)
            require(known.size in 1..32)
            require(
                known.size < 32 || known.any { it.deviceId == identity.deviceId },
            ) { "Clipboard device limit reached" }
            val ownKey = ownKey()
            require(known.any { it.deviceId == localDevice && it.signingPublicKey == ownKey })
            known.firstOrNull { it.deviceId == identity.deviceId }?.let { require(it.signingPublicKey == key) }
            require(outgoing.size < MAX_PENDING)
            val invitationId = codecs.base64Encode(CryptographyRandom.nextBytes(16))
            val proposal =
                admissions.propose(
                    ClipboardAdmissionClaim(
                        clipboard,
                        invitationId,
                        localDevice,
                        ClipboardMemberKey(identity.deviceId, key),
                        known,
                    ),
                    secureStore.secureKeyPair.signKeyPair.privateKey,
                )
            val offer =
                ClipboardInvitation(
                    invitationId,
                    clipboard,
                    localDevice,
                    identity.deviceId,
                    known,
                    proposal,
                )
            outgoing[offer.id] = Outgoing(offer, key, now())
            offer
        }

    suspend fun receive(verified: VerifiedClipboardPeerRequest): ClipboardInvitationStatus =
        mutex.withLock {
            expire()
            val request = verified.request
            require(request.operation == ClipboardPeerOperation.PROPOSE_JOIN && request.target == localDevice)
            require(secureStore.existCryptPublicKey(request.sender))
            val offer = Json.decodeFromString<ClipboardInvitation>(request.payload)
            require(offer.id.isNotBlank() && offer.id.length <= 128 && offer.clipboard == request.clipboard)
            require(offer.inviter == request.sender && offer.recipient == localDevice && offer.inviter != localDevice)
            require(
                offer.members.size in 1..32 &&
                    offer.members
                        .map { it.deviceId }
                        .toSet()
                        .size == offer.members.size,
            )
            require(offer.members.any { it.deviceId == offer.inviter })
            offer.members.forEach {
                require(it.deviceId.isNotBlank() && it.deviceId.length <= 256)
                canonicalKey(it.signingPublicKey)
            }
            incoming[offer.id]?.let {
                require(it.offer == offer)
                return@withLock it.status
            }
            require(incoming.size < MAX_PENDING)
            val approved = alreadyApproved(offer)
            val existingConsent = if (approved) consentFor(offer) else null
            if (existingConsent != null) database.transaction { admissions.recordConsented(existingConsent) }
            val status =
                ClipboardInvitationStatus(
                    offer.id,
                    offer.clipboard,
                    if (approved) ClipboardInvitationState.ACCEPTED else ClipboardInvitationState.PENDING,
                    if (approved) ownKey() else null,
                    existingConsent,
                )
            incoming[offer.id] = Pending(offer, now(), status)
            publish()
            status
        }

    /** No HTTP route calls this method. It belongs to the receiving device's Accept button. */
    suspend fun accept(
        id: String,
        contributeLocal: Boolean,
    ): ClipboardInvitationStatus =
        mutex.withLock {
            expire()
            val pending = requireNotNull(incoming[id]) { "Invitation expired" }
            require(secureStore.existCryptPublicKey(pending.offer.inviter)) { "Device is no longer paired" }
            if (pending.status.state == ClipboardInvitationState.ACCEPTED) return@withLock pending.status
            require(pending.status.state == ClipboardInvitationState.PENDING)
            val key = secureStore.secureKeyPair.signKeyPair.publicKey
            val encoded = codecs.base64Encode(serializer.encodeSignPublicKey(key))
            val consent = consentFor(pending.offer)
            members.joinApprovedClipboard(
                pending.offer.clipboard,
                localDevice,
                key,
                pending.offer.members,
                contributeLocal,
                onJoined = { consent?.let(admissions::recordConsented) },
            )
            pending.status =
                pending.status.copy(
                    state = ClipboardInvitationState.ACCEPTED,
                    signingPublicKey = encoded,
                    admission = consent,
                )
            publish()
            pending.status
        }

    suspend fun decline(id: String) =
        mutex.withLock {
            expire()
            val pending = requireNotNull(incoming[id])
            require(pending.status.state == ClipboardInvitationState.PENDING)
            pending.status = pending.status.copy(state = ClipboardInvitationState.DECLINED)
            publish()
        }

    suspend fun status(verified: VerifiedClipboardPeerRequest): ClipboardInvitationStatus =
        mutex.withLock {
            expire()
            val request = verified.request
            require(request.operation == ClipboardPeerOperation.JOIN_STATUS && request.target == localDevice)
            val id = Json.decodeFromString<ClipboardInvitationLookup>(request.payload).id
            require(id.isNotBlank() && id.length <= 128)
            val pending =
                incoming[id] ?: return@withLock ClipboardInvitationStatus(
                    id,
                    request.clipboard,
                    ClipboardInvitationState.EXPIRED,
                )
            require(request.sender == pending.offer.inviter && request.clipboard == pending.offer.clipboard)
            pending.status
        }

    suspend fun confirm(reply: VerifiedClipboardPeerReply): ClipboardInvitationState =
        mutex.withLock {
            expire()
            require(
                reply.request.operation == ClipboardPeerOperation.JOIN_STATUS && reply.request.sender == localDevice,
            )
            val id = Json.decodeFromString<ClipboardInvitationLookup>(reply.request.payload).id
            val pending = requireNotNull(outgoing[id]) { "Invitation expired" }
            val status = Json.decodeFromString<ClipboardInvitationStatus>(reply.payload)
            require(reply.peer == pending.offer.recipient && reply.request.clipboard == pending.offer.clipboard)
            require(secureStore.existCryptPublicKey(reply.peer))
            require(status.id == id && status.clipboard == pending.offer.clipboard)
            require(pending.state == ClipboardInvitationState.PENDING || pending.state == status.state)
            if (status.state == ClipboardInvitationState.ACCEPTED) {
                val accepted = canonicalKey(requireNotNull(status.signingPublicKey))
                require(accepted == pending.expectedKey) { "Device signing identity changed while joining" }
                val proposal = pending.offer.admissionProposal
                if (proposal != null) {
                    val admission = requireNotNull(status.admission) { "Device did not provide signed join consent" }
                    require(admission.proposal == proposal) { "Consent belongs to another invitation" }
                    val plan = admissions.prepare(pending.offer.clipboard, listOf(admission))
                    database.transaction { admissions.commit(plan) }
                } else {
                    members.approveMember(
                        pending.offer.clipboard,
                        reply.peer,
                        serializer.decodeSignPublicKey(codecs.base64Decode(accepted)),
                    )
                }
            }
            pending.state = status.state
            status.state
        }

    private suspend fun consentFor(offer: ClipboardInvitation): ClipboardAdmission? {
        val proposal = offer.admissionProposal ?: return null
        require(
            proposal.claim ==
                ClipboardAdmissionClaim(
                    offer.clipboard,
                    offer.id,
                    offer.inviter,
                    ClipboardMemberKey(localDevice, ownKey()),
                    offer.members,
                ),
        ) { "Admission proposal does not match invitation" }
        return admissions.consent(proposal, secureStore.secureKeyPair.signKeyPair.privateKey)
    }

    /** A restart may lose the pending receipt, but not consent already committed to SQL. */
    private suspend fun alreadyApproved(offer: ClipboardInvitation): Boolean {
        if (database.sharedClipboardDatabaseQueries
                .getWorkspace(offer.clipboard)
                .executeAsOneOrNull()
                ?.enabled !=
            true
        ) {
            return false
        }
        val known = members.memberKeys(offer.clipboard).associate { it.deviceId to it.signingPublicKey }
        if (known[localDevice] != ownKey()) return false
        return offer.members.all { known[it.deviceId] == canonicalKey(it.signingPublicKey) }
    }

    private suspend fun ownKey(): String =
        codecs.base64Encode(serializer.encodeSignPublicKey(secureStore.secureKeyPair.signKeyPair.publicKey))

    private suspend fun canonicalKey(encoded: String): String {
        require(encoded.length in 1..512)
        return codecs.base64Encode(
            serializer.encodeSignPublicKey(serializer.decodeSignPublicKey(codecs.base64Decode(encoded))),
        )
    }

    private fun expire() {
        val cutoff = now() - TTL_MILLIS
        incoming.entries.removeAll { it.value.created <= cutoff }
        outgoing.entries.removeAll { it.value.created <= cutoff }
        publish()
    }

    private fun publish() {
        incomingState.value =
            incoming.values.filter { it.status.state == ClipboardInvitationState.PENDING }.map { it.offer }
    }

    private companion object {
        const val MAX_PENDING = 16
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}
