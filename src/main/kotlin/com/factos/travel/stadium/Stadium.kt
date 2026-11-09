package com.factos.travel.stadium

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository

/** Big6 홈구장. 행은 Flyway(V2)가 넣는다. geography 열(location)은 DB가 lat/lng로 만들어서 여기엔 없다 */
@Entity
@Table(name = "stadium")
class Stadium(
    @Id val id: Long,
    val club: String,
    val name: String,
    @Column(name = "name_ko") val nameKo: String,
    val city: String,
    val lat: Double,
    val lng: Double,
    val transit: String?,
)

interface StadiumRepository : JpaRepository<Stadium, Long> {
    fun findByClub(club: String): Stadium?
    fun findAllByOrderById(): List<Stadium>
}
