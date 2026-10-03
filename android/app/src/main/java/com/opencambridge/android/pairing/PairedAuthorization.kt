package com.opencambridge.android.pairing

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class PairedRequest(val job: Job) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PairedRequest>
}

@Serializable data class PairedPc(val pcId: String, val name: String, val credentialId: String, val token: String)
@Serializable data class PhoneIdentityProof(val phoneId: String, val port: Int, val proof: String)

/** One lock covers credential lookup and job registration: revoke cannot race between them. */
class PairedAuthorization {
    private var credentials = emptyList<PairedPc>()
    private val jobs = mutableMapOf<String, MutableSet<Job>>()
    @Synchronized fun replace(next: List<PairedPc>) {
        val removed = credentials.filter { old -> next.none { it.credentialId == old.credentialId && it.token == old.token } }
        credentials = next.toList()
        removed.forEach { pc -> jobs.remove(pc.credentialId)?.toList()?.forEach { it.cancel() } }
    }
    @Synchronized fun authorize(token: String?, job: Job): Boolean {
        if (token == null || token.length != 64 || !job.isActive) return false
        val pc = credentials.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) } ?: return false
        jobs.getOrPut(pc.credentialId) { mutableSetOf() }.add(job)
        job.invokeOnCompletion { synchronized(this) { jobs[pc.credentialId]?.remove(job) } }
        return true
    }
    @Synchronized fun identify(phoneId: String, port: Int, credentialId: String, nonce: String): PhoneIdentityProof? {
        try { hexBytes(credentialId, 16); hexBytes(nonce, 16) } catch (_: IllegalArgumentException) { return null }
        val pc = credentials.firstOrNull { it.credentialId == credentialId } ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pc.token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val proof = mac.doFinal("ocb-identify-v1:$phoneId:$credentialId:$nonce:$port".toByteArray(Charsets.UTF_8)).hex()
        return PhoneIdentityProof(phoneId, port, proof)
    }
}
