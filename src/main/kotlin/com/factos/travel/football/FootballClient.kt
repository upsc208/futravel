package com.factos.travel.football

import com.factos.travel.club.Club
import com.factos.travel.fixture.FixtureService
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Duration
import java.time.OffsetDateTime

/** football-svc `GET /api/matches/clubs/{club}/upcoming` 응답 한 줄 (factos-BE UpcomingMatchResponse) */
data class UpcomingMatchDto(
    val matchId: String?,
    val opponent: String,
    val matchDate: String,
    val isHome: Boolean,
    val tournament: String,
)

/**
 * football-svc REST 클라이언트. 서비스 사이는 **공개 API로만** 통신한다 (DB를 같이 보지 않는다).
 * compose 안에서는 내부망 주소(`http://backend:8080`)로 부른다.
 */
@Component
class FootballClient(@Value("\${travel.football.base-url}") baseUrl: String) {
    private val client = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(3))
            setReadTimeout(Duration.ofSeconds(10))
        })
        .build()

    /** 그 로그인 쿠키의 주인 (football-svc `GET /api/auth/me`). 비로그인·만료면 null */
    fun me(sessionToken: String): JsonNode? =
        client.get().uri("/api/auth/me")
            .header("Cookie", "factos_session=$sessionToken")
            .exchange { _, res -> if (res.statusCode.is2xxSuccessful) res.bodyTo(JsonNode::class.java) else null }

    fun upcoming(club: Club): List<UpcomingMatchDto> =
        client.get().uri("/api/matches/clubs/{club}/upcoming", club.id).retrieve().body<List<UpcomingMatchDto>>() ?: emptyList()
}

/**
 * 처음 뜰 때 football-svc API로 팀별 일정을 한 번 받아 둔다.
 * 이벤트는 "바뀐 것"만 오므로, 이 서비스가 처음 생겼거나 오래 꺼져 있었으면 API로 채워야 한다.
 * 실패해도 앱은 뜬다 (이벤트로 따라잡는다).
 */
@Component
class FixtureBackfill(
    private val football: FootballClient,
    private val fixtures: FixtureService,
    @Value("\${travel.football.backfill-on-start:true}") private val enabled: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!enabled) return
        Thread({ backfill() }, "fixture-backfill").apply { isDaemon = true }.start()
    }

    /** @return 받아 온 일정 수 */
    fun backfill(): Int {
        var total = 0
        for (club in Club.entries) {
            val list = runCatching { football.upcoming(club) }
                .onFailure { log.warn("⚠️ [Backfill] {} 일정을 못 받았습니다: {}", club.id, it.message) }
                .getOrNull() ?: continue
            list.filter { it.matchId != null }.forEach {
                fixtures.upsert(it.matchId!!, club.id, it.opponent, OffsetDateTime.parse(it.matchDate), it.isHome, it.tournament)
            }
            total += list.size
        }
        log.info("✅ [Backfill] football-svc에서 일정 {}건을 받았습니다", total)
        return total
    }
}
