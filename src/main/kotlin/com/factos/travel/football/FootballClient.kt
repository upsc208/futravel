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
 * football-svc와 같이 재시작되면 처음엔 연결이 안 되므로, 못 받은 팀만 간격을 늘려 가며 다시 시도한다
 * (기본 10초부터 두 배, 최대 2분 간격으로 8번 ≈ 10분). 끝내 실패해도 앱은 뜬다 (이벤트로 따라잡는다).
 */
@Component
class FixtureBackfill(
    private val football: FootballClient,
    private val fixtures: FixtureService,
    @Value("\${travel.football.backfill-on-start:true}") private val enabled: Boolean,
    @Value("\${travel.football.backfill-attempts:8}") private val attempts: Int,
    @Value("\${travel.football.backfill-retry-base-ms:10000}") private val retryBaseMs: Long,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!enabled) return
        Thread({ backfill(attempts) }, "fixture-backfill").apply { isDaemon = true }.start()
    }

    /** 못 받은 팀만 [maxAttempts]번까지 다시. @return 받아 온 일정 수 */
    fun backfill(maxAttempts: Int = 1): Int {
        var pending = Club.entries.toList()
        var total = 0
        var delay = retryBaseMs
        for (attempt in 1..maxAttempts) {
            val last = attempt == maxAttempts
            val failed = pending.filter { club ->
                runCatching { total += load(club) }
                    .onFailure { if (last) log.warn("⚠️ [Backfill] {} 일정을 못 받았습니다: {}", club.id, it.message) }
                    .isFailure
            }
            if (failed.isEmpty() || last) break
            log.info("⏳ [Backfill] football-svc 연결 안 됨 ({}팀) — {}초 뒤 다시 ({}/{})", failed.size, delay / 1000, attempt, maxAttempts)
            Thread.sleep(delay)
            delay = (delay * 2).coerceAtMost(120_000)
            pending = failed
        }
        log.info("✅ [Backfill] football-svc에서 일정 {}건을 받았습니다", total)
        return total
    }

    private fun load(club: Club): Int {
        val list = football.upcoming(club)
        list.filter { it.matchId != null }.forEach {
            fixtures.upsert(it.matchId!!, club.id, it.opponent, OffsetDateTime.parse(it.matchDate), it.isHome, it.tournament)
        }
        return list.size
    }
}
