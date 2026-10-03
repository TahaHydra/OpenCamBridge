package com.opencambridge.android.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PairingPersistence {
    fun load(): String?
    fun save(value: String)
}
@Serializable private data class StoredPairings(val phoneId: String, val pcs: List<PairedPc>)

/** Persists before publishing authorization; a failed disk write cannot hand out a new token. */
class PairingStore(private val persistence: PairingPersistence) {
    private val state: MutableStateFlow<List<PairedPc>>
    val pcs: StateFlow<List<PairedPc>> get() = state
    val phoneId: String
    val authorization = PairedAuthorization()
    init {
        val saved = persistence.load()?.let { Json.decodeFromString<StoredPairings>(it) }
            ?: StoredPairings(randomHex(16), emptyList()).also { persistence.save(Json.encodeToString(it)) }
        hexBytes(saved.phoneId, 16)
        require(saved.pcs.size <= 32 && saved.pcs.map { it.pcId }.distinct().size == saved.pcs.size)
        saved.pcs.forEach { hexBytes(it.pcId, 16); hexBytes(it.credentialId, 16); hexBytes(it.token, 32); PairingInput.name(it.name) }
        phoneId = saved.phoneId
        state = MutableStateFlow(saved.pcs)
        authorization.replace(saved.pcs)
    }
    @Synchronized fun issue(pcId: String, name: String): PairedPc {
        hexBytes(pcId, 16); PairingInput.name(name)
        val pc = PairedPc(pcId, name, randomHex(16), randomHex(32))
        val next = state.value.filterNot { it.pcId == pcId } + pc
        require(next.size <= 32) { "Revoke an unused PC before pairing another" }
        save(next)
        return pc
    }
    @Synchronized fun revoke(pcId: String) { save(state.value.filterNot { it.pcId == pcId }) }
    private fun save(next: List<PairedPc>) {
        persistence.save(Json.encodeToString(StoredPairings(phoneId, next)))
        authorization.replace(next)
        state.value = next
    }
    companion object {
        @Volatile private var instance: PairingStore? = null
        fun get(context: Context): PairingStore = instance ?: synchronized(this) {
            instance ?: PairingStore(KeystorePairingPersistence(context.applicationContext)).also { instance = it }
        }
    }
}

private class KeystorePairingPersistence(context: Context) : PairingPersistence {
    private val prefs = context.getSharedPreferences("ocb_paired_pcs_v1", Context.MODE_PRIVATE)
    private val alias = "ocb-paired-pcs-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun load(): String? {
        val encoded = prefs.getString("encrypted", null) ?: return null
        require(encoded.length <= 65536)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(alias.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    override fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray())
        check(prefs.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Unable to save pairing" }
    }
}
