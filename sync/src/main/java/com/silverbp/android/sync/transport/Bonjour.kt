package com.silverbp.android.sync.transport

/**
 * mDNS / Bonjour service discovery wire constants. Android registers via
 * [android.net.nsd.NsdManager], iOS via [NWBrowser]; both speak DNS-SD with
 * the same service-type string.
 *
 * Phase 1 stub — concrete `NsdManager` registration + discovery lands in
 * Phase 1.2.
 */
object SyncBonjour {
    const val SERVICE_TYPE = "_silverbp-sync._tcp"
    const val DOMAIN = "local."
    const val TXT_KEY_DEVICE_ID = "did"
    const val TXT_KEY_PUBKEY_FINGERPRINT = "pkfp"
    const val TXT_KEY_PROTOCOL_VERSION = "v"
    const val CURRENT_PROTOCOL_VERSION = "1"
}
