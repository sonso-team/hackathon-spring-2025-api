package org.sonso.hackathonspring2025api.sender

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sonso.hackathonspring2025api.config.WebSocketHandler
import org.sonso.hackathonspring2025api.dto.response.RaceResponse
import org.sonso.hackathonspring2025api.service.SimulationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import kotlin.system.measureTimeMillis

@Service
class SocketSender(
    private val webSocketHandler: WebSocketHandler,
    private val objectMapper: ObjectMapper,
    private val simulationService: SimulationService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelay = 1000L)
    fun sendData() {
        val time = measureTimeMillis {
            // Каждый вызов шедуллера проверяет текущее состояние и формирует нужный ответ (SYNC или UPDATE)
            val response: RaceResponse = simulationService.runScheduledLogic()

            // Преобразуем в JSON и отправляем во фронт через WebSocket
            val json = objectMapper.writeValueAsString(response)
            logger.info("Response type: {}", response.type)
            logger.debug("Send data: {}", json)
            val message = TextMessage(json)

            webSocketHandler.sessions.forEach { session ->
                if (session.isOpen) {
                    session.sendMessage(message)
                }
            }
        }
        logger.info("Time for operation: $time ms")
    }
}
