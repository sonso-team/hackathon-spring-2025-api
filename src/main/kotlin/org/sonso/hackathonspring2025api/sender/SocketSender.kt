package org.sonso.hackathonspring2025api.sender

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.random.Random

@Service
class SocketSender(
    private val messagingTemplate: SimpMessagingTemplate
) {
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelay = 1000L)
    fun sendData() {
        val data = mapOf(
            "timestamp" to LocalDateTime.now().toString(),
            "value" to Random.nextInt(100)
        )
        logger.debug("timestamp: {}, value: {}", data["timestamp"], data["value"])
        messagingTemplate.convertAndSend("/topic/data", data)
    }

}
