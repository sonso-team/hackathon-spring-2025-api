package org.sonso.hackathonspring2025api.controller

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sonso.hackathonspring2025api.dto.request.AuthRequest
import org.sonso.hackathonspring2025api.dto.request.SetStatsRequest
import org.sonso.hackathonspring2025api.dto.response.AuthResponse
import org.sonso.hackathonspring2025api.service.StatsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdminController(private val statsService: StatsService) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @PostMapping("/auth")
    fun auth(
        @RequestBody request: AuthRequest
    ): ResponseEntity<AuthResponse> {
        logger.info("Request to admin panel auth")
        return ResponseEntity.ok(statsService.auth(request))
    }

    @PutMapping("/set-stats")
    fun setStats(
        @RequestBody request: SetStatsRequest
    ): ResponseEntity<Map<String, String>> {
        logger.info("Request to set stats for ${request.personName} person")
        statsService.setStats(request)
        return ResponseEntity.ok(mapOf("message" to "OK"))
    }

}
