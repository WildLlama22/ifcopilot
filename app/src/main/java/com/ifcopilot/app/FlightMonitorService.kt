package com.ifcopilot.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.abs

class FlightMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "ifcopilot_monitor"
        const val NOTIF_ID = 1
        const val ACTION_TOGA = "com.ifcopilot.app.ACTION_TOGA"

        // Shared, app-process-wide handles so the UI can read live values
        // and trigger the TOGA button without binding to the service.
        val client = ConnectApiClient()
        var toga: TogaController? = null
        var callouts: CalloutEngine? = null
        var currentProfile: AircraftProfile = AircraftProfiles.GENERIC
        var enabledFeatures = FeatureFlags()

        // Latest readouts, exposed for the UI
        var lastAltitudeAgl: Double = 0.0
        var lastIas: Double = 0.0
        var lastGearDown: Boolean = true
        var lastFlapState: Int = 0
        var lastOnGround: Boolean = true
        var connected: Boolean = false
    }

    data class FeatureFlags(
        var vSpeedCallouts: Boolean = true,
        var gearWarning: Boolean = true,
        var retardCallout: Boolean = true,
        var windshearWarning: Boolean = true,
        var configConfirmations: Boolean = true
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    // ---- Flight phase tracking ----
    private var hasCalledV1 = false
    private var hasCalledVr = false
    private var hasCalledPositiveRate = false
    private var hasCalled80or100 = false
    private var wasOnGround = true
    private var lastGearLeverDown = true
    private var lastFlapIndex = -1
    private var lastGearWarnAt = 0L

    // Windshear tracking: rolling headwind samples keyed by AGL band during
    // approach (50-1300ft) and shortly after rotation.
    private var headwindAt50ft: Double? = null
    private var headwindAtRotation: Double? = null
    private var rotationTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        callouts = CalloutEngine(this, serviceScope)
        toga = TogaController(client)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always (re)post the foreground notification so the service isn't
        // killed, whether this start came from launch or a notification tap.
        val notification = buildNotification(if (connected) "Connected — monitoring flight" else "Not connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (intent?.action == ACTION_TOGA) {
            // Fired from the notification's TOGA button — lets you engage
            // TOGA while Infinite Flight is fullscreen, without switching
            // apps. Useful for single-device setups.
            toga?.engage(
                scope = serviceScope,
                targetFraction = 0.95f,
                durationMs = 3000L
            )
        }

        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (client.isConnected) {
                        pollOnce()
                        connected = true
                    } else {
                        connected = false
                    }
                } catch (e: Exception) {
                    connected = false
                }
                delay(200) // ~5Hz
            }
        }
    }

    private suspend fun pollOnce() {
        val agl = readDouble("aircraft/0/altitude_agl") ?: return
        val ias = readDouble("aircraft/0/indicated_airspeed") ?: return
        val onGround = readBool("aircraft/0/is_on_ground") ?: true
        val gearDown = readBool("aircraft/0/systems/landing_gear/lever_state") ?: true
        val flapState = readInt("aircraft/0/systems/flaps/state") ?: 0
        val windDir = readDouble("environment/wind_direction_true") ?: 0.0
        val windSpeed = readDouble("environment/wind_velocity") ?: 0.0
        val heading = readDouble("aircraft/0/heading_true") ?: 0.0
        val vs = readDouble("aircraft/0/vertical_speed") ?: 0.0

        lastAltitudeAgl = agl
        lastIas = ias
        lastGearDown = gearDown
        lastFlapState = flapState
        lastOnGround = onGround

        val headwind = windSpeed * cos(Math.toRadians(windDir - heading))

        handleTakeoffCallouts(ias, onGround, vs)
        handleConfirmations(gearDown, flapState)
        if (enabledFeatures.gearWarning) handleGearWarning(agl, gearDown, onGround)
        if (enabledFeatures.retardCallout) handleRetardCallout(agl, onGround)
        if (enabledFeatures.windshearWarning) handleWindshear(agl, headwind, onGround)

        // reset takeoff-roll flags once back on ground and stopped
        if (onGround && ias < 30) {
            hasCalledV1 = false
            hasCalledVr = false
            hasCalledPositiveRate = false
            hasCalled80or100 = false
            headwindAtRotation = null
            headwindAt50ft = null
        }
        wasOnGround = onGround
    }

    private fun handleTakeoffCallouts(ias: Double, onGround: Boolean, vs: Double) {
        if (!enabledFeatures.vSpeedCallouts) return
        val profile = currentProfile

        if (onGround && ias >= profile.callout80or100 && !hasCalled80or100) {
            hasCalled80or100 = true
            callouts?.speak("${profile.callout80or100} knots", CalloutPriority.CALLOUT, "callout_80_100")
        }
        if (onGround && ias >= profile.v1 && !hasCalledV1) {
            hasCalledV1 = true
            callouts?.speak("V1", CalloutPriority.CALLOUT, "callout_v1")
        }
        if (onGround && ias >= profile.vr && !hasCalledVr) {
            hasCalledVr = true
            callouts?.speak("Rotate", CalloutPriority.CALLOUT, "callout_vr")
        }
        // Positive rate: just left the ground and climbing
        if (wasOnGround && !onGround && !hasCalledPositiveRate) {
            hasCalledPositiveRate = true
            rotationTimeMs = System.currentTimeMillis()
            callouts?.speak("Positive rate", CalloutPriority.CALLOUT, "callout_positive_rate")
        }
    }

    private fun handleConfirmations(gearDown: Boolean, flapIndex: Int) {
        if (!enabledFeatures.configConfirmations) return

        if (lastGearLeverDown != gearDown) {
            lastGearLeverDown = gearDown
            val text = if (gearDown) "Gear down" else "Gear up"
            callouts?.speak(text, CalloutPriority.CONFIRM, "confirm_gear", minGapMs = 500)
        }
        if (lastFlapIndex != flapIndex) {
            lastFlapIndex = flapIndex
            // Flap "state" is an index into the aircraft's flap stops; label
            // it generically since exact detent names vary by aircraft.
            val text = if (flapIndex == 0) "Flaps up" else "Flaps $flapIndex"
            callouts?.speak(text, CalloutPriority.CONFIRM, "confirm_flaps", minGapMs = 500)
        }
    }

    private fun handleGearWarning(agl: Double, gearDown: Boolean, onGround: Boolean) {
        if (onGround) return
        if (agl in 1.0..500.0 && !gearDown) {
            val now = System.currentTimeMillis()
            if (now - lastGearWarnAt > 4000) {
                lastGearWarnAt = now
                callouts?.speak("Gear, Gear", CalloutPriority.WARNING, "warn_gear", minGapMs = 0)
            }
        }
    }

    private fun handleRetardCallout(agl: Double, onGround: Boolean) {
        if (!currentProfile.isAirbus || onGround) return
        // Classic Airbus auto-callout window: ~20ft down to touchdown if
        // thrust levers haven't been pulled to idle. We don't have direct
        // throttle-lever-position read wired here by default, so this fires
        // on altitude alone as an approximation; tie to throttle lever state
        // if you resolve "aircraft/0/systems/engines/0/throttle_lever" and
        // check it's above idle threshold.
        if (agl in 1.0..20.0) {
            callouts?.speak("Retard", CalloutPriority.CALLOUT, "callout_retard", minGapMs = 2000)
        }
    }

    private fun handleWindshear(agl: Double, headwind: Double, onGround: Boolean) {
        if (onGround) return

        // Case 1: descending through 50-1300ft AGL, compare current headwind
        // against the value captured when passing 50ft.
        if (agl in 45.0..55.0 && headwindAt50ft == null) {
            headwindAt50ft = headwind
        }
        headwindAt50ft?.let { base ->
            if (agl in 50.0..1300.0) {
                if (base - headwind >= 15.0) {
                    callouts?.speak("Windshear, Windshear, Windshear", CalloutPriority.WARNING, "warn_windshear", minGapMs = 15000)
                }
            } else if (agl > 1300.0) {
                headwindAt50ft = null // out of the monitored band, reset for next approach
            }
        }

        // Case 2: after rotation, compare current headwind against headwind
        // captured at the moment of liftoff.
        if (headwindAtRotation == null && rotationTimeMs > 0) {
            headwindAtRotation = headwind
        }
        headwindAtRotation?.let { base ->
            val secondsSinceRotation = (System.currentTimeMillis() - rotationTimeMs) / 1000.0
            if (secondsSinceRotation in 0.0..120.0) {
                if (base - headwind >= 15.0) {
                    callouts?.speak("Windshear, Windshear, Windshear", CalloutPriority.WARNING, "warn_windshear", minGapMs = 15000)
                }
            }
        }
    }

    // ---- state read helpers ----

    private suspend fun readDouble(path: String): Double? {
        val entry = client.resolve(path) ?: return null
        return client.getState(entry)?.asDouble()
    }

    private suspend fun readBool(path: String): Boolean? {
        val entry = client.resolve(path) ?: return null
        return client.getState(entry)?.asBoolean()
    }

    private suspend fun readInt(path: String): Int? {
        val entry = client.resolve(path) ?: return null
        return client.getState(entry)?.asDouble()?.toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "IF Copilot Monitor", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val togaIntent = Intent(this, FlightMonitorService::class.java).apply { action = ACTION_TOGA }
        val togaPendingIntent = PendingIntent.getService(
            this, 0, togaIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IF Copilot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "TOGA", togaPendingIntent)
            .build()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        callouts?.shutdown()
        client.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
