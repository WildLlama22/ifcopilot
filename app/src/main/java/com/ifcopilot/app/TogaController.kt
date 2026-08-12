package com.ifcopilot.app

import kotlinx.coroutines.*

/**
 * Smoothly ramps the throttle state to a target (95% by default) over a
 * fixed duration, rather than snapping it instantly - closer to how a real
 * TOGA press results in a commanded, not instantaneous, thrust increase.
 *
 * Uses "simulator/throttle" which is a global single-lever throttle state
 * in the Connect API manifest. If you want independent per-engine control,
 * swap the resolved path to "aircraft/0/systems/engines/{n}/throttle_lever"
 * for each engine and drive them in parallel.
 */
class TogaController(private val client: ConnectApiClient) {

    private var job: Job? = null

    fun isRunning() = job?.isActive == true

    fun engage(scope: CoroutineScope, targetFraction: Float = 0.95f, durationMs: Long = 3000L, onDone: (() -> Unit)? = null) {
        job?.cancel()
        FlightMonitorService.lastTogaError = null

        // Exact path is a best guess (never confirmed against IF's real
        // manifest) - fall back to a fuzzy search for anything throttle-
        // related if it's not found under that exact name, and surface a
        // real error instead of doing nothing silently.
        val throttleEntry = client.resolve("simulator/throttle")
            ?: client.resolveFuzzy("throttle")

        if (throttleEntry == null) {
            FlightMonitorService.lastTogaError =
                "No throttle state found in the manifest (looked for \"simulator/throttle\" and anything containing \"throttle\"). " +
                "The real path name differs from what's assumed here - check Logcat for the manifest dump to find the correct one."
            onDone?.invoke()
            return
        }

        job = scope.launch(Dispatchers.IO) {
            try {
                val startValue = (client.getState(throttleEntry)?.asDouble() ?: 0.0).toFloat()
                val steps = (durationMs / 50L).toInt().coerceAtLeast(1)
                val stepDelay = durationMs / steps

                for (i in 1..steps) {
                    if (!isActive) return@launch
                    val t = i.toFloat() / steps
                    // ease-out so the ramp feels like a commanded thrust increase, not linear robotic motion
                    val eased = 1f - (1f - t) * (1f - t)
                    val value = startValue + (targetFraction - startValue) * eased
                    client.setStateFloat(throttleEntry, value.coerceIn(0f, 1f))
                    delay(stepDelay)
                }
            } catch (e: Exception) {
                FlightMonitorService.lastTogaError = "TOGA failed: ${e.message ?: e.javaClass.simpleName}"
            }
            onDone?.invoke()
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
