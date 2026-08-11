package com.ifcopilot.app

/**
 * Reference performance data per aircraft type.
 *
 * NOTE: Infinite Flight's Connect API does not expose live gross weight, so
 * these V-speeds are computed at a single fixed reference weight per type.
 * The reference weight is the midpoint between each aircraft's approximate
 * Operating Empty Weight and MTOW (OEW + 0.5 * (MTOW - OEW)) - not a
 * certified limit like MLW, but a self-consistent "moderately loaded"
 * proxy that lands at roughly the same ~75-78% of MTOW for every type here,
 * unlike MLW which varies 72-85% across types for structural-design reasons
 * unrelated to typical payload. V-speeds are scaled to this weight using
 * sqrt(weight ratio), a standard rough approximation for how reference
 * speeds move with weight - not manufacturer FCOM/AFM data. Treat all
 * figures here as approximations for sim immersion only.
 */
data class AircraftProfile(
    val displayName: String,
    val matchKeywords: List<String>,
    val isAirbus: Boolean,          // controls "Retard" auto-callout at flare
    val callout80or100: Int,        // 80 for Boeing-style, 100 for Airbus-style
    val referenceWeightKg: Int,
    val v1: Int,
    val vr: Int,
    val v2: Int
)

object AircraftProfiles {

    val ALL = listOf(
        AircraftProfile(
            displayName = "Airbus A320",
            matchKeywords = listOf("a320", "a-320"),
            isAirbus = true,
            callout80or100 = 100,
            referenceWeightKg = 61000, // OEW/MTOW midpoint
            v1 = 124,
            vr = 128,
            v2 = 131
        ),
        AircraftProfile(
            displayName = "Airbus A321",
            matchKeywords = listOf("a321", "a-321"),
            isAirbus = true,
            callout80or100 = 100,
            referenceWeightKg = 73550, // OEW/MTOW midpoint
            v1 = 132,
            vr = 138,
            v2 = 141
        ),
        AircraftProfile(
            displayName = "Boeing 737-800",
            matchKeywords = listOf("737-800", "737800", "b738"),
            isAirbus = false,
            callout80or100 = 80,
            referenceWeightKg = 60215, // OEW/MTOW midpoint
            v1 = 126,
            vr = 132,
            v2 = 137
        ),
        AircraftProfile(
            displayName = "Boeing 737 MAX 8",
            matchKeywords = listOf("737 max", "737max", "b38m", "max 8"),
            isAirbus = false,
            callout80or100 = 80,
            referenceWeightKg = 63277, // OEW/MTOW midpoint
            v1 = 129,
            vr = 135,
            v2 = 141
        ),
        AircraftProfile(
            displayName = "Boeing 777-300ER",
            matchKeywords = listOf("777-300", "777300", "b77w", "773er"),
            isAirbus = false,
            callout80or100 = 80,
            referenceWeightKg = 259682, // OEW/MTOW midpoint
            v1 = 139,
            vr = 146,
            v2 = 154
        ),
        AircraftProfile(
            displayName = "Boeing 787-9",
            matchKeywords = listOf("787-9", "787900", "b789"),
            isAirbus = false,
            callout80or100 = 80,
            referenceWeightKg = 191000, // OEW/MTOW midpoint
            v1 = 130,
            vr = 137,
            v2 = 144
        ),
        AircraftProfile(
            displayName = "Generic / Other",
            matchKeywords = emptyList(),
            isAirbus = false,
            callout80or100 = 80,
            referenceWeightKg = 70000,
            v1 = 130,
            vr = 138,
            v2 = 145
        )
    )

    val GENERIC = ALL.last()

    /** Best-effort match against the aircraft name/livery string IF reports. */
    fun matchByName(name: String): AircraftProfile {
        val lower = name.lowercase()
        return ALL.firstOrNull { profile ->
            profile.matchKeywords.any { lower.contains(it) }
        } ?: GENERIC
    }
}
