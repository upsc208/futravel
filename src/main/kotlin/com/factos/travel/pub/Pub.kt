package com.factos.travel.pub

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * 펍. 기본 목록은 OpenStreetMap에서 받아 오고([PubSource.OSM]), 관리자가 직접 넣을 수도 있다.
 * "이 팀 팬 펍"([club])·"축구 중계"([showsFootball])·한국어 메모([note])는 사람이 붙인다 — 다시 가져와도 지우지 않는다.
 * geography 열(location)은 DB가 lat/lng로 만든다 (V1__init.sql)
 */
@Entity
@Table(name = "pub")
class Pub(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var name: String,
    var club: String?,
    var lat: Double,
    var lng: Double,
    var address: String?,
    var note: String?,
    @Enumerated(EnumType.STRING) var kind: PubKind = PubKind.PUB,
    var city: String? = null,
    @Enumerated(EnumType.STRING) val source: PubSource = PubSource.ADMIN,
    @Column(name = "osm_ref") val osmRef: String? = null,
    var website: String? = null,
    @Column(name = "opening_hours") var openingHours: String? = null,
    @Column(name = "shows_football") var showsFootball: Boolean = false,
    var hidden: Boolean = false,
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "created_at", insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,
) {
    /** 사람이 붙인 정보가 있는지 — 있으면 OSM에서 사라져도 지우지 않는다 */
    val curated get() = club != null || showsFootball || !note.isNullOrBlank()
}

enum class PubKind { PUB, BAR }
enum class PubSource { ADMIN, OSM }

/** 검색 결과 한 줄. 기준점(경기장·내 위치)이 있으면 거리(미터)도 */
interface PubRow {
    val id: Long
    val name: String
    val club: String?
    val kind: String
    val city: String?
    val lat: Double
    val lng: Double
    val address: String?
    val website: String?
    val openingHours: String?
    val showsFootball: Boolean
    val note: String?
    val source: String
    val distanceMeters: Double?
}

private const val COLUMNS = """
    p.id AS id, p.name AS name, p.club AS club, p.kind AS kind, p.city AS city, p.lat AS lat, p.lng AS lng,
    p.address AS address, p.website AS website, p.opening_hours AS openingHours, p.shows_football AS showsFootball,
    p.note AS note, p.source AS source
"""

interface PubRepository : JpaRepository<Pub, Long> {

    /**
     * [club] 홈구장에서 [radiusMeters] 안의 펍. 그 팀 팬 펍 → 중계하는 곳 → 가까운 순.
     * ST_DWithin은 GIST 인덱스(idx_pub_location)를 탄다 (geography라 단위가 미터)
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT $COLUMNS, ST_Distance(p.location, s.location) AS distanceMeters
            FROM pub p JOIN stadium s ON s.club = :club
            WHERE NOT p.hidden AND ST_DWithin(p.location, s.location, :radius)
            ORDER BY (p.club = :club) IS TRUE DESC, p.shows_football DESC, distanceMeters
            LIMIT :limit
        """,
    )
    fun findNearStadium(@Param("club") club: String, @Param("radius") radiusMeters: Double, @Param("limit") limit: Int = 50): List<PubRow>

    /** 한 점(내 위치)에서 [radiusMeters] 안. [footballOnly]면 중계하는 곳·팀 팬 펍만 */
    @Query(
        nativeQuery = true,
        value = """
            SELECT $COLUMNS, ST_Distance(p.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distanceMeters
            FROM pub p
            WHERE NOT p.hidden
              AND ST_DWithin(p.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
              AND (NOT :footballOnly OR p.shows_football OR p.club IS NOT NULL)
            ORDER BY (p.club = :club) IS TRUE DESC, distanceMeters
            LIMIT :limit
        """,
    )
    fun findNear(
        @Param("lat") lat: Double, @Param("lng") lng: Double, @Param("radius") radiusMeters: Double,
        @Param("club") club: String, @Param("footballOnly") footballOnly: Boolean, @Param("limit") limit: Int = 50,
    ): List<PubRow>

    /** 경기장에 못 갈 때 ①: 영국 어디든 [club] 팬이 모이는 펍. [city]가 있으면 그 지역만 */
    @Query(
        nativeQuery = true,
        value = """
            SELECT $COLUMNS, CAST(NULL AS DOUBLE PRECISION) AS distanceMeters
            FROM pub p
            WHERE NOT p.hidden AND p.club = :club
              AND (CAST(:city AS VARCHAR) IS NULL OR p.city = :city)
            ORDER BY p.city, p.name
            LIMIT :limit
        """,
    )
    fun findFanPubs(@Param("club") club: String, @Param("city") city: String?, @Param("limit") limit: Int = 100): List<PubRow>

    /**
     * 경기장에 못 갈 때 ②: 스포츠 중계하는 펍 (어느 팀 팬 펍도 아닌 곳). 사람이 확인한 곳(메모 있음)이 먼저.
     * 팬 펍과 쿼리를 나눈 이유: 같이 정렬·LIMIT하면 중계 펍 수천 곳이 팬 펍을 밀어낸다
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT $COLUMNS, CAST(NULL AS DOUBLE PRECISION) AS distanceMeters
            FROM pub p
            WHERE NOT p.hidden AND p.shows_football AND p.club IS NULL
              AND (CAST(:city AS VARCHAR) IS NULL OR p.city = :city)
            ORDER BY (p.note IS NOT NULL) DESC, p.city, p.name
            LIMIT :limit
        """,
    )
    fun findFootballPubs(@Param("city") city: String?, @Param("limit") limit: Int = 100): List<PubRow>

    fun findAllBySourceAndCity(source: PubSource, city: String): List<Pub>

    fun countBySource(source: PubSource): Long

    @Query("SELECT p.city AS city, COUNT(p) AS count FROM Pub p WHERE p.hidden = false AND p.city IS NOT NULL GROUP BY p.city ORDER BY p.city")
    fun countByCity(): List<CityCount>
}

interface CityCount {
    val city: String
    val count: Long
}
