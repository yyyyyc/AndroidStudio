package com.example.superprices

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Settlement names by CBS code; store files give the city as a code (e.g. 5000 = Tel Aviv). */
object Cities {
    @Volatile private var map: Map<Int, String>? = null

    fun name(context: Context, code: Int?, raw: String): String {
        if (code == null) return raw
        val m = map ?: synchronized(this) {
            map ?: context.assets.open("cities.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { l ->
                    val t = l.indexOf('\t')
                    if (t < 0) null else l.substring(0, t).toIntOrNull()?.let { it to l.substring(t + 1) }
                }.toMap()
            }.also { map = it }
        }
        // Some Shufersal stores carry internal codes (e.g. 10018) that aren't settlements.
        return m[code] ?: ""
    }
}

object Geo {
    /** The phone's position, or null. Needs the coarse location permission. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 30 * 60_000) return recent
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return recent
        }
        val fresh = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, { cont.resume(it) }, context.mainLooper)
                }
            }
        }
        return fresh ?: recent
    }

    val available get() = Geocoder.isPresent()

    /**
     * Looks up an address with the phone's geocoder: null when there's no match, an
     * exception when the lookup itself failed (e.g. no network). Blocking.
     */
    fun lookup(context: Context, query: String): Pair<Double, Double>? {
        @Suppress("DEPRECATION")
        return Geocoder(context, Locale("he", "IL")).getFromLocationName(query, 1)?.firstOrNull()
            ?.let { it.latitude to it.longitude }
    }

    fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val out = FloatArray(1)
        Location.distanceBetween(a.first, a.second, b.first, b.second, out)
        return out[0] / 1000.0
    }
}
