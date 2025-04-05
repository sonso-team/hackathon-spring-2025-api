package org.sonso.hackathonspring2025api.dto.request

data class AuthRequest(
    val login: String,
    val password: String,
)
