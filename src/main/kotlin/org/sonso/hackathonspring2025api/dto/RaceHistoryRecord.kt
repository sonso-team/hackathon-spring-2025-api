package org.sonso.hackathonspring2025api.dto

import java.io.Serializable

data class RaceHistoryRecord(
    val raceIndex: Int,
    val results: List<RaceResult>
): Serializable
