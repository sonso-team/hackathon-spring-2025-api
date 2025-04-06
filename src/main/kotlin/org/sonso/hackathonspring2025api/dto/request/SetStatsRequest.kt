package org.sonso.hackathonspring2025api.dto.request

data class SetStatsRequest(
    val personName: String,
    val reactionTime: Double? = null,
    val acceleration: Double? = null,
    val maxSpeed: Double? = null,
    val lsf: Double? = null,
)
