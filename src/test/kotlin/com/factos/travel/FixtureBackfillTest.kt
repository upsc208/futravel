package com.factos.travel

import com.factos.travel.fixture.FixtureRepository
import com.factos.travel.football.FixtureBackfill
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress

/**
 * 처음 뜰 때 football-svc REST API로 일정을 받아 둔다. football-svc는 로컬 가짜 서버로 대신한다
 * (응답 모양은 factos-BE `GET /api/matches/clubs/{club}/upcoming`과 같다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestContainersConfig::class)
class FixtureBackfillTest {

    companion object {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/matches/clubs/") { ex ->
                val club = ex.requestURI.path.split("/")[4]
                val body = when (club) {
                    "arsenal" -> """[{"matchId":"5795523","opponent":"Newcastle","matchDate":"2099-11-22T02:30:00+09:00","isHome":false,"tournament":"프리미어리그"},
                                    {"matchId":"6106259","opponent":"Dortmund","matchDate":"2099-11-25T05:00:00+09:00","isHome":true,"tournament":"챔피언스리그"}]"""
                    "chelsea" -> """[{"matchId":null,"opponent":"Leeds","matchDate":"2099-11-22T00:00:00+09:00","isHome":true,"tournament":"프리미어리그"}]"""
                    else -> "[]"
                }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                // 리버풀은 football-svc 오류를 흉내 낸다 — 다른 팀은 계속 받아야 한다
                if (club == "liverpool") ex.sendResponseHeaders(500, -1) else ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { if (club != "liverpool") it.write(body) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun football(registry: DynamicPropertyRegistry) {
            registry.add("travel.football.base-url") { "http://localhost:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    @Autowired private lateinit var backfill: FixtureBackfill
    @Autowired private lateinit var fixtures: FixtureRepository

    @Test
    fun `팀별 일정을 API로 받아 저장하고, 한 팀이 실패해도 나머지는 받는다`() {
        backfill.backfill()

        val arsenal = fixtures.findAll().filter { it.club == "arsenal" }.sortedBy { it.kickoff }
        assertThat(arsenal.map { it.opponent }).containsExactly("Newcastle", "Dortmund")
        assertThat(arsenal[1].isHome).isTrue()
        assertThat(arsenal[1].tournament).isEqualTo("챔피언스리그")
        assertThat(fixtures.findAll().none { it.club == "chelsea" }).isTrue() // matchId 없는 일정은 건너뜀

        backfill.backfill() // 다시 받아도 늘어나지 않는다 (upsert)
        assertThat(fixtures.findAll().count { it.club == "arsenal" }).isEqualTo(2)
    }
}
