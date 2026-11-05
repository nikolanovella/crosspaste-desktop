package com.crosspaste.sync.journal

import com.crosspaste.Database
import com.crosspaste.secure.SecureKeyPairSerializer
import com.crosspaste.utils.CryptographyUtils
import com.crosspaste.utils.getCodecsUtils
import dev.whyoleg.cryptography.algorithms.ECDSA
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ClipboardAdmissionClaim(
    val clipboard: String,
    val invitationId: String,
    val sponsor: String,
    val recipient: ClipboardMemberKey,
    val roster: List<ClipboardMemberKey>,
)

@Serializable
data class ClipboardAdmissionProposal(
    val claim: ClipboardAdmissionClaim,
    val sponsorSignature: String,
)

@Serializable
data class ClipboardAdmission(
    val proposal: ClipboardAdmissionProposal,
    val recipientSignature: String,
)

/** Portable proof of an existing member's invitation AND the new device's local consent. */
class ClipboardAdmissionStore(
    private val database: Database,
    private val serializer: SecureKeyPairSerializer,
) {
    private val queries = database.sharedClipboardDatabaseQueries
    private val codecs = getCodecsUtils()
    private val json = Json { encodeDefaults = true }

    internal data class Plan(
        val clipboard: String,
        val initialKeys: Map<String, String>,
        val keys: Map<String, String>,
        val admissions: List<ClipboardAdmission>,
    )

    suspend fun propose(
        claim: ClipboardAdmissionClaim,
        privateKey: ECDSA.PrivateKey,
    ): ClipboardAdmissionProposal {
        validateClaim(claim)
        return ClipboardAdmissionProposal(
            claim,
            codecs.base64Encode(
                CryptographyUtils.signData(privateKey) {
                    sponsorBytes(claim)
                },
            ),
        )
    }

    /** Only called after local acceptance (or exact previously committed consent) by the invitation service. */
    internal suspend fun consent(
        proposal: ClipboardAdmissionProposal,
        privateKey: ECDSA.PrivateKey,
    ): ClipboardAdmission {
        validateClaim(proposal.claim)
        val sponsor = proposal.claim.roster.single { it.deviceId == proposal.claim.sponsor }
        verifySponsor(proposal, sponsor.signingPublicKey)
        return ClipboardAdmission(
            proposal,
            codecs.base64Encode(
                CryptographyUtils.signData(privateKey) {
                    recipientBytes(proposal)
                },
            ),
        )
    }

    internal suspend fun prepare(
        clipboard: String,
        incoming: List<ClipboardAdmission>,
    ): Plan {
        require(incoming.size <= 32)
        require(queries.getWorkspace(clipboard).executeAsOneOrNull()?.enabled == true)
        val initial = queries.getMembers(clipboard).executeAsList().associate { it.deviceId to it.signingPublicKey }
        require(initial.size in 1..32)
        val keys = initial.toMutableMap()
        val remaining = incoming.toMutableList()
        val ordered = mutableListOf<ClipboardAdmission>()
        incoming.forEach {
            require(it.proposal.claim.clipboard == clipboard)
            validateClaim(it.proposal.claim)
        }
        while (remaining.isNotEmpty()) {
            val next =
                remaining.firstOrNull { it.proposal.claim.sponsor in keys }
                    ?: throw IllegalArgumentException("Admission has no approved sponsor")
            val proposal = next.proposal
            val claim = proposal.claim
            verifySponsor(proposal, keys.getValue(claim.sponsor))
            require(next.recipientSignature.length in 1..256)
            val key = canonicalKey(claim.recipient.signingPublicKey)
            require(
                validSignature(decodeKey(key), next.recipientSignature, recipientBytes(proposal)),
            ) {
                "Admission has no valid recipient consent"
            }
            val known = keys[claim.recipient.deviceId]
            require(known == null || known == key) { "Admission replaces an approved identity" }
            keys[claim.recipient.deviceId] = key
            require(keys.size <= 32)
            ordered.add(next)
            remaining.remove(next)
        }
        return Plan(clipboard, initial, keys, ordered)
    }

    /** Rechecks the trust snapshot, then commits inside the caller's history/invitation transaction. */
    internal fun commit(plan: Plan) {
        require(queries.getWorkspace(plan.clipboard).executeAsOneOrNull()?.enabled == true)
        val current =
            queries
                .getMembers(
                    plan.clipboard,
                ).executeAsList()
                .associate { it.deviceId to it.signingPublicKey }
        plan.initialKeys.forEach { (device, key) -> require(current[device] == key) { "Approved membership changed" } }
        plan.keys.forEach { (device, key) -> require(current[device] == null || current[device] == key) }
        require((current.keys + plan.keys.keys).size <= 32)
        plan.keys.forEach { (device, key) -> if (device !in current) queries.insertMember(plan.clipboard, device, key) }
        plan.admissions.forEach { admission ->
            val claim = admission.proposal.claim
            val previous = queries.getAdmission(plan.clipboard, claim.recipient.deviceId).executeAsOneOrNull()
            if (previous ==
                null
            ) {
                queries.insertAdmission(plan.clipboard, claim.recipient.deviceId, json.encodeToString(admission))
            }
        }
    }

    /** The invitation service has verified this proof and just committed exact local consent. */
    internal fun recordConsented(admission: ClipboardAdmission) {
        val claim = admission.proposal.claim
        require(queries.getWorkspace(claim.clipboard).executeAsOneOrNull()?.enabled == true)
        (claim.roster + claim.recipient).forEach { member ->
            require(
                queries.getMember(claim.clipboard, member.deviceId).executeAsOneOrNull()?.signingPublicKey ==
                    member.signingPublicKey,
            )
        }
        if (queries.getAdmission(claim.clipboard, claim.recipient.deviceId).executeAsOneOrNull() == null) {
            queries.insertAdmission(claim.clipboard, claim.recipient.deviceId, json.encodeToString(admission))
        }
    }

    internal suspend fun signingKey(
        plan: Plan,
        device: String,
    ): ECDSA.PublicKey? = plan.keys[device]?.let { decodeKey(it) }

    internal fun all(clipboard: String): List<ClipboardAdmission> =
        queries.getAdmissions(clipboard).executeAsList().map { row ->
            json.decodeFromString<ClipboardAdmission>(row.proof).also {
                require(
                    it.proposal.claim.clipboard == clipboard && it.proposal.claim.recipient.deviceId == row.deviceId,
                )
            }
        }

    private suspend fun validateClaim(claim: ClipboardAdmissionClaim) {
        require(claim.clipboard.isNotBlank() && claim.clipboard.length <= 256)
        require(claim.invitationId.isNotBlank() && claim.invitationId.length <= 128)
        require(claim.sponsor.isNotBlank() && claim.sponsor.length <= 256)
        require(claim.recipient.deviceId.isNotBlank() && claim.recipient.deviceId.length <= 256)
        require(claim.sponsor != claim.recipient.deviceId)
        require(
            claim.roster.size in 1..32 &&
                claim.roster
                    .map { it.deviceId }
                    .toSet()
                    .size == claim.roster.size,
        )
        require(claim.roster.any { it.deviceId == claim.sponsor })
        claim.roster.firstOrNull { it.deviceId == claim.recipient.deviceId }?.let {
            require(it == claim.recipient) { "Admission conflicts with its own roster" }
        }
        require((claim.roster.map { it.deviceId } + claim.recipient.deviceId).toSet().size <= 32)
        (claim.roster + claim.recipient).forEach {
            require(it.deviceId.isNotBlank() && it.deviceId.length <= 256)
            require(canonicalKey(it.signingPublicKey) == it.signingPublicKey) { "Noncanonical admission key" }
        }
    }

    private suspend fun verifySponsor(
        proposal: ClipboardAdmissionProposal,
        knownKey: String,
    ) {
        require(proposal.sponsorSignature.length in 1..256)
        require(
            proposal.claim.roster
                .single { it.deviceId == proposal.claim.sponsor }
                .signingPublicKey == knownKey,
        )
        require(
            validSignature(decodeKey(knownKey), proposal.sponsorSignature, sponsorBytes(proposal.claim)),
        ) {
            "Invalid admission sponsor signature"
        }
    }

    private suspend fun validSignature(
        key: ECDSA.PublicKey,
        signature: String,
        bytes: ByteArray,
    ): Boolean =
        try {
            CryptographyUtils.verifyData(key, codecs.base64Decode(signature)) { bytes }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun canonicalKey(encoded: String): String {
        require(encoded.length in 1..512)
        return codecs.base64Encode(serializer.encodeSignPublicKey(decodeKey(encoded)))
    }

    private suspend fun decodeKey(encoded: String): ECDSA.PublicKey =
        serializer.decodeSignPublicKey(codecs.base64Decode(encoded))

    private fun sponsorBytes(claim: ClipboardAdmissionClaim): ByteArray =
        (
            "CrossPaste/shared-clipboard/admission/sponsor/v1\n" +
                json.encodeToString(claim.copy(roster = claim.roster.sortedBy { it.deviceId }))
        ).encodeToByteArray()

    private fun recipientBytes(proposal: ClipboardAdmissionProposal): ByteArray =
        (
            "CrossPaste/shared-clipboard/admission/consent/v1\n" +
                json.encodeToString(
                    proposal.copy(claim = proposal.claim.copy(roster = proposal.claim.roster.sortedBy { it.deviceId })),
                )
        ).encodeToByteArray()
}
