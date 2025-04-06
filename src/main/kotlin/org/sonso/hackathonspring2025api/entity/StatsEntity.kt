package org.sonso.hackathonspring2025api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "stats")
data class StatsEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    val id: Int? = null,

    @Column(name = "person_name", nullable = false, unique = true)
    val personName: String,

    @Column(name = "reaction_time", nullable = true, unique = false)
    val reactionTime: Double?,

    @Column(name = "acceleration", nullable = true, unique = false)
    val acceleration: Double?,

    @Column(name = "max_speed", nullable = true, unique = false)
    val maxSpeed: Double?,

    @Column(name = "lsf", nullable = true, unique = false)
    val lsf: Double?,
)
