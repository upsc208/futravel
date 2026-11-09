package com.factos.travel.pub

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/** 펍. 관리자가 넣는다. geography 열(location)은 DB가 lat/lng로 만든다 (V1__init.sql) */
@Entity
@Table(name = "pub")
class Pub(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    val name: String,
    val club: String?,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val note: String?,
    @Column(name = "created_at", insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,
)

/** 경기장에서 가까운 펍 한 줄 (거리 포함) */
interface NearbyPub {
    val id: Long
    val name: String
    val club: String?
    val lat: Double
    val lng: Double
    val address: String?
    val note: String?
    val distanceMeters: Double
}

interface PubRepository : JpaRepository<Pub, Long> {

    /**
     * [club] 홈구장에서 [radiusMeters] 안의 펍, 가까운 순 (PostGIS geography라 거리 단위가 미터다).
     * ST_DWithin은 GIST 인덱스(idx_pub_location)를 탄다
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT p.id AS id, p.name AS name, p.club AS club, p.lat AS lat, p.lng AS lng,
                   p.address AS address, p.note AS note,
                   ST_Distance(p.location, s.location) AS distanceMeters
            FROM pub p JOIN stadium s ON s.club = :club
            WHERE ST_DWithin(p.location, s.location, :radius)
            ORDER BY distanceMeters
            LIMIT 50
        """,
    )
    fun findNearStadium(@Param("club") club: String, @Param("radius") radiusMeters: Double): List<NearbyPub>
}
