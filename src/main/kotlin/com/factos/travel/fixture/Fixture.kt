package com.factos.travel.fixture

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

enum class FixtureStatus { SCHEDULED, CANCELLED, FINISHED }

/**
 * 경기 일정 사본 (팀 기준 — 맞대결은 두 행). 원본은 football-svc다.
 * 여기서는 킥오프 알림·직관 기록에 필요한 만큼만 들고, 이벤트·API로 맞춘다.
 */
@Entity
@Table(name = "fixture")
class Fixture(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "match_id") val matchId: String,
    val club: String,
    var opponent: String,
    var kickoff: OffsetDateTime,
    @Column(name = "is_home") var isHome: Boolean,
    var tournament: String,
    @Enumerated(EnumType.STRING) var status: FixtureStatus = FixtureStatus.SCHEDULED,
    @Column(name = "home_score") var homeScore: Int? = null,
    @Column(name = "away_score") var awayScore: Int? = null,
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface FixtureRepository : JpaRepository<Fixture, Long> {
    fun findByMatchIdAndClub(matchId: String, club: String): Fixture?
    fun findAllByMatchId(matchId: String): List<Fixture>
    fun findAllByClubAndStatusAndKickoffAfterOrderByKickoff(club: String, status: FixtureStatus, after: OffsetDateTime): List<Fixture>
}

/** 처리한 이벤트 id (football-svc 아웃박스 id) — 같은 이벤트를 두 번 반영하지 않으려고 */
@Entity
@Table(name = "processed_event")
class ProcessedEvent(
    @Id @Column(name = "event_id") val eventId: Long,
    @Column(name = "processed_at") val processedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, Long>
