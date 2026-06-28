package com.silverbp.android.sync.pairing

/**
 * One-shot pairing payload encoded into a QR code shown by the initiator.
 * The joiner scans, performs ECDH(initiator.pk, joiner.sk), and shows a
 * 6-digit SAS for the user to visually confirm.
 *
 * URL form: `silverbp://pair?v=1&pk=<base64>&svc=<bonjourName>&did=<deviceId>`
 *
 * `pk` is the initiator's static/long-term X25519 public key (32 bytes,
 * base64) — the pinned identity key, not an ephemeral per-session key.
 * After pairing it is stored in EncryptedSharedPreferences so future syncs
 * over the same Wi-Fi can be authenticated against this pinned key; the
 * 6-digit SAS confirmation guards the initial exchange against MITM.
 *
 * Phase 1 stub — concrete encoder + scanner UI in Phase 1.2.
 */
data class QrPairingPayload(
    val version: Int = CURRENT_VERSION,
    val publicKey: ByteArray,
    val bonjourServiceName: String,
    val deviceId: String,
) {
    init {
        require(publicKey.size == 32) {
            "publicKey must be a 32-byte X25519 raw form, got ${publicKey.size}"
        }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(bonjourServiceName.isNotBlank()) { "bonjourServiceName must not be blank" }
    }

    /**
     * Encode this payload as a `silverbp://pair?...` URL the QR encoder
     * embeds. Format must remain byte-stable with iOS [QRPairingPayload]:
     *
     *   `silverbp://pair?v=<int>&pk=<base64-url-safe>&svc=<urlencoded>&did=<urlencoded>`
     *
     * Whitespace is rejected at parse time — neither pubkey base64 nor a
     * device id should contain spaces.
     */
    fun toUrl(): String {
        val pkB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)
        val svc = java.net.URLEncoder.encode(bonjourServiceName, Charsets.UTF_8)
        val did = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8)
        return "$SCHEME://$HOST?v=$version&pk=$pkB64&svc=$svc&did=$did"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrPairingPayload) return false
        return version == other.version &&
            publicKey.contentEquals(other.publicKey) &&
            bonjourServiceName == other.bonjourServiceName &&
            deviceId == other.deviceId
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + bonjourServiceName.hashCode()
        result = 31 * result + deviceId.hashCode()
        return result
    }

    companion object {
        const val SCHEME = "silverbp"
        const val HOST = "pair"
        const val CURRENT_VERSION = 1

        /**
         * Parse a `silverbp://pair?...` URL into a payload. Throws
         * [IllegalArgumentException] on any malformed input — callers
         * should surface this as "invalid QR" in UI.
         */
        fun parse(url: String): QrPairingPayload {
            val u = runCatching { java.net.URI(url) }.getOrElse {
                throw IllegalArgumentException("malformed pairing URL: ${it.message}")
            }
            require(u.scheme == SCHEME) { "expected scheme '$SCHEME', got '${u.scheme}'" }
            require(u.host == HOST) { "expected host '$HOST', got '${u.host}'" }
            val params = (u.rawQuery ?: "").split('&')
                .mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq < 0) null
                    else java.net.URLDecoder.decode(it.substring(0, eq), Charsets.UTF_8) to
                        java.net.URLDecoder.decode(it.substring(eq + 1), Charsets.UTF_8)
                }
                .toMap()
            val v = params["v"]?.toIntOrNull()
                ?: throw IllegalArgumentException("missing or invalid 'v'")
            require(v == CURRENT_VERSION) { "unsupported pairing protocol version: $v" }
            val pkB64 = params["pk"] ?: throw IllegalArgumentException("missing 'pk'")
            val pk = runCatching {
                java.util.Base64.getUrlDecoder().decode(pkB64)
            }.getOrElse {
                throw IllegalArgumentException("invalid base64 'pk': ${it.message}")
            }
            require(pk.size == 32) { "pk must be 32 bytes, got ${pk.size}" }
            val svc = params["svc"] ?: throw IllegalArgumentException("missing 'svc'")
            val did = params["did"] ?: throw IllegalArgumentException("missing 'did'")
            return QrPairingPayload(version = v, publicKey = pk, bonjourServiceName = svc, deviceId = did)
        }
    }
}
