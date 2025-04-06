package org.sonso.hackathonspring2025api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "stats")
class StatsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    var id: Int? = null

    @Column(name = "person_name", nullable = false, unique = true)
    var personName: String = ""

    @Column(name = "reaction_time", nullable = true, unique = false)
    var reactionTime: Double? = null

    @Column(name = "acceleration", nullable = true, unique = false)
    var acceleration: Double? = null

    @Column(name = "max_speed", nullable = true, unique = false)
    var maxSpeed: Double? = null

    @Column(name = "lsf", nullable = true, unique = false)
    var lsf: Double? = null
}
