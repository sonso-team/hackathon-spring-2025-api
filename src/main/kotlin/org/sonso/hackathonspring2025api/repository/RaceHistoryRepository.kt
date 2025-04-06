package org.sonso.hackathonspring2025api.repository

import org.sonso.hackathonspring2025api.dto.RaceHistoryRecord
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Repository

@Repository
class RaceHistoryRepository(
    private val redisTemplate: RedisTemplate<String, Any>
) {
    companion object {
        private const val RACES_KEY = "ALL_RACES"  // ключ, где храним всю историю
    }

    fun clearHistory() {
        redisTemplate.delete(RACES_KEY)
    }

    /**
     * Загрузить все записи из Redis, или пустой список, если нет.
     */
    fun findAllRecords(): List<RaceHistoryRecord> {
        val ops = redisTemplate.opsForList()
        val size = ops.size(RACES_KEY) ?: 0
        if (size == 0L) return emptyList()

        // LINDEX по всем, либо LRANGE
        val rawList = ops.range(RACES_KEY, 0, size - 1) ?: emptyList()
        return rawList.filterIsInstance<RaceHistoryRecord>()
    }

    /**
     * Добавить новую запись в конец списка.
     */
    fun addRecord(record: RaceHistoryRecord) {
        val ops = redisTemplate.opsForList()
        ops.rightPush(RACES_KEY, record)
    }

    /**
     * Вернуть последние N записей (с конца).
     */
    fun findLastNRecords(n: Long): List<RaceHistoryRecord> {
        val ops = redisTemplate.opsForList()
        val size = ops.size(RACES_KEY) ?: 0
        if (size == 0L) return emptyList()
        val start = maxOf(0, size - n)
        val end = size - 1
        val rawList = ops.range(RACES_KEY, start, end) ?: emptyList()
        return rawList.filterIsInstance<RaceHistoryRecord>()
    }
}
