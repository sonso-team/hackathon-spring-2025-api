package org.sonso.hackathonspring2025api.dto

data class Athlete(
    val name: String,
    var mu: Double,
    var sigma1: Double,
    var sigma2: Double,
    var history: MutableList<RaceResult> = mutableListOf()
)