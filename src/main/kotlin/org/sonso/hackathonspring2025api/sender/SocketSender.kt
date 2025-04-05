package org.sonso.hackathonspring2025api.sender

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sonso.hackathonspring2025api.config.WebSocketHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.system.measureTimeMillis

@Service
class SocketSender(
    private val webSocketHandler: WebSocketHandler,
    private val objectMapper: ObjectMapper
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelay = 1000L)
    fun sendData() {
        val time = measureTimeMillis {
            val data = mapOf(
                "timestamp" to LocalDateTime.now().toString(),
                "value" to Random.nextInt(100)
            )
            val jsonData = objectMapper.writeValueAsString(data)
            logger.debug("Send data: {}", jsonData)
            val message = TextMessage(jsonData)

            webSocketHandler.sessions.forEach { session ->
                if (session.isOpen) {
                    session.sendMessage(message)
                }
            }
        }
        logger.debug("Time for operation: $time ms")
    }
}
