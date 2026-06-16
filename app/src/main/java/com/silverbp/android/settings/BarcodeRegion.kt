package com.silverbp.android.settings

import android.content.Context
import android.telephony.TelephonyManager
import com.silverbp.android.BuildConfig
import java.util.Locale

/**
 * Decides whether the food **barcode-scan** entry point should be shown for the
 * current device, based on region.
 *
 * Barcode lookup goes through Open Food Facts (OFF), whose coverage is strong in
 * Western/Japanese packaged-goods markets but **poor for Taiwan convenience-store
 * hot food / 鮮食** — and much of that food has no barcode at all (PLU at the
 * register). Showing "Scan barcode" to a Taiwan user invites the misunderstanding
 * that they can scan their 超商便當. So we only surface barcode scanning in regions
 * where OFF actually pays off, and steer everyone else to photo recognition.
 *
 * Region signal: SIM country first (offline, no permission, closest to real
 * location), then network country, then the device Locale as a last resort.
 */
object BarcodeRegion {

    /**
     * ISO 3166-1 alpha-2 countries where Open Food Facts coverage is good enough
     * that barcode scanning is genuinely useful. Deliberately excludes TW.
     * Kept in one place so the list is easy to grow as coverage improves.
     */
    private val OFF_ALLOWLIST: Set<String> = setOf(
        "US", "CA", "MX",
        "GB", "IE", "FR", "DE", "ES", "IT", "NL", "BE", "LU",
        "CH", "AT", "PT", "SE", "NO", "DK", "FI", "PL", "CZ",
        "AU", "NZ", "JP",
    )

    /**
     * True when the barcode entry point should be visible. Debug builds always
     * show it so the feature can be exercised on a Taiwan test device/emulator.
     */
    fun isBarcodeSupported(context: Context): Boolean =
        BuildConfig.DEBUG || isCountrySupported(deviceCountry(context))

    /** Pure allowlist check (case-insensitive); unit-testable without a Context. */
    fun isCountrySupported(country: String): Boolean =
        country.uppercase(Locale.ROOT) in OFF_ALLOWLIST

    /** Uppercase ISO country: SIM → network → Locale. Never throws. */
    fun deviceCountry(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.uppercase(Locale.ROOT).orEmpty()
        if (sim.isNotBlank()) return sim
        val network = tm?.networkCountryIso?.uppercase(Locale.ROOT).orEmpty()
        if (network.isNotBlank()) return network
        return Locale.getDefault().country.uppercase(Locale.ROOT)
    }
}
