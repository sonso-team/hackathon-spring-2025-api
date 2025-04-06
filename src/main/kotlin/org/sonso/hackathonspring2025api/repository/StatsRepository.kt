package org.sonso.hackathonspring2025api.repository

import org.sonso.hackathonspring2025api.entity.StatsEntity
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface StatsRepository: CrudRepository<StatsEntity, Int> {
    fun getStatsEntityByPersonName(personName: String): StatsEntity?
}
