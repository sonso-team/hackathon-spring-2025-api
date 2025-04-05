package org.sonso.hackathonspring2025api.dto

import java.io.Serializable

data class RaceResult(
    val name: String,
    val finishTime: Double,
    val place: Int
): Serializable
