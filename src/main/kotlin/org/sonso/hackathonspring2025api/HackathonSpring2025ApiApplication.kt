package org.sonso.hackathonspring2025api

import org.sonso.hackathonspring2025api.config.properties.AdminProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    AdminProperties::class
)
class HackathonSpring2025ApiApplication

fun main(args: Array<String>) {
    runApplication<HackathonSpring2025ApiApplication>(*args)
}
