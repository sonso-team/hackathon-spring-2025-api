package org.sonso.hackathonspring2025api.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.admin")
data class AdminProperties(
    val login: String,
    val password: String
)
