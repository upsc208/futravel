package com.factos.travel.fixture

import com.factos.travel.club.Club
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * football-svc에서 온 경기 정보를 우리 사본에 반영한다.
 * 이벤트 계약: factos-BE docs/events.md (match.scheduled / updated / cancelled / finished)
 */
@Service
class FixtureService(
    private val fixtures: FixtureRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 경기 이벤트 하나를 반영한다. 중복 제거·트랜잭션은 [com.factos.travel.event.EventApplier]가 한다 */
    @Transactional
    fun applyMatchEvent(eventId: Long, type: String, payload: JsonNode) {
        when (type) {
            "match.scheduled", "match.updated" -> upsert(
                matchId = payload.text("matchId"),
                club = payload.text("club"),
                opponent = payload.text("opponent"),
                kickoff = OffsetDateTime.parse(payload.text("kickoff")),
                isHome = payload.path("isHome").asBoolean(),
                tournament = payload.text("tournament"),
            )
            "match.cancelled" ->
                fixtures.findByMatchIdAndClub(payload.text("matchId"), payload.text("club"))?.let {
                    it.status = FixtureStatus.CANCELLED
                    it.updatedAt = OffsetDateTime.now()
                }
            // 경기당 한 번 온다 — 그 경기를 가진 팀 행을 모두 끝난 것으로
            "match.finished" -> fixtures.findAllByMatchId(payload.text("matchId")).forEach {
                it.status = FixtureStatus.FINISHED
                it.homeScore = payload.path("homeScore").asInt()
                it.awayScore = payload.path("awayScore").asInt()
                it.updatedAt = OffsetDateTime.now()
            }
            else -> log.info("ℹ️ [Event] 모르는 이벤트는 건너뜁니다: {} (id={})", type, eventId)
        }
    }

    /** 일정 한 줄을 넣거나 고친다 (이벤트·API 백필 공용). 우리 팀이 아니면 무시 */
    @Transactional
    fun upsert(matchId: String, club: String, opponent: String, kickoff: OffsetDateTime, isHome: Boolean, tournament: String) {
        if (Club.fromId(club) == null) return
        val f = fixtures.findByMatchIdAndClub(matchId, club)
        if (f == null) {
            fixtures.save(Fixture(matchId = matchId, club = club, opponent = opponent, kickoff = kickoff, isHome = isHome, tournament = tournament))
            return
        }
        f.opponent = opponent
        f.kickoff = kickoff
        f.isHome = isHome
        f.tournament = tournament
        if (f.status == FixtureStatus.CANCELLED) f.status = FixtureStatus.SCHEDULED // 다시 잡힌 경기
        f.updatedAt = OffsetDateTime.now()
    }

    private fun JsonNode.text(field: String): String =
        path(field).asText().takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("이벤트에 $field 가 없습니다")
}
