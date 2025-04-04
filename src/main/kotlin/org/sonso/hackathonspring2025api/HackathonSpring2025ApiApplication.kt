package org.sonso.hackathonspring2025api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class HackathonSpring2025ApiApplication

fun main(args: Array<String>) {
	runApplication<HackathonSpring2025ApiApplication>(*args)
}
