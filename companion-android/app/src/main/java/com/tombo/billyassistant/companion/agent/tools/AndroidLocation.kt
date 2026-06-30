package com.tombo.billyassistant.companion.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun currentAndroidLocation(
    context: Context,
    recentLocationMs: Long = 30L * 60L * 1000L,
    currentLocationTimeoutMs: Long = 2_500L,
): Location? {
    if (!hasLocationPermission(context)) {
        return null
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val lastKnown = manager.getProviders(true)
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
    if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < recentLocationMs) {
        return lastKnown
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return lastKnown
    }
    val provider = when {
        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
        runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
        else -> null
    } ?: return lastKnown
    val latch = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    var current: Location? = null
    val cancellation = CancellationSignal()
    try {
        manager.getCurrentLocation(provider, cancellation, executor) { location ->
            current = location
            latch.countDown()
        }
        latch.await(currentLocationTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: SecurityException) {
        return lastKnown
    } finally {
        cancellation.cancel()
        executor.shutdownNow()
    }
    return current ?: lastKnown
}

private fun hasLocationPermission(context: Context): Boolean {
    return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
