package org.sonso.hackathonspring2025api.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sonso.hackathonspring2025api.config.properties.AdminProperties
import org.sonso.hackathonspring2025api.dto.request.AuthRequest
import org.sonso.hackathonspring2025api.dto.request.SetStatsRequest
import org.sonso.hackathonspring2025api.dto.response.AuthResponse
import org.sonso.hackathonspring2025api.entity.StatsEntity
import org.sonso.hackathonspring2025api.repository.StatsRepository
import org.springframework.stereotype.Service

@Service
class StatsService(
    private val adminProperties: AdminProperties,
    private val statsRepository: StatsRepository
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    fun auth(request: AuthRequest): AuthResponse {
        logger.debug("login: ${request.login}, password: ${request.password}")
        return AuthResponse(
            isAuth = request.login == adminProperties.login && request.password == adminProperties.password
        )
    }

    fun setStats(request: SetStatsRequest) {
        logger.debug("Request to setStats: {}", request)
        val oldEntity = statsRepository.getStatsEntityByPersonName(request.personName)

        val newEntity = StatsEntity(
            id = oldEntity?.id,
            personName = request.personName,
            reactionTime = request.reactionTime ?: oldEntity?.reactionTime,
            acceleration = request.acceleration ?: oldEntity?.acceleration,
            maxSpeed = request.maxSpeed ?: oldEntity?.maxSpeed,
            lsf = request.lsf ?: oldEntity?.lsf,
        )
        logger.debug("New stats Entity: {}", newEntity)

        statsRepository.save(newEntity)
    }
}