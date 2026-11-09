package com.factos.travel

import com.factos.travel.event.EventConsumer
import com.factos.travel.fixture.FixtureRepository
import com.factos.travel.fixture.FixtureStatus
import com.factos.travel.fixture.ProcessedEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * football-svc가 Redis Stream에 올리는 것과 같은 모양으로 이벤트를 넣고 (factos-BE docs/events.md),
 * 소비자가 일정 사본에 반영하는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig::class)
class MatchEventFlowTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var fixtures: FixtureRepository
    @Autowired private lateinit var processed: ProcessedEventRepository

    companion object {
        private val nextId = AtomicLong(1000)
    }

    @BeforeEach
    fun setUp() {
        fixtures.deleteAll()
        processed.deleteAll()
    }

    /** factos-BE OutboxRelay와 같은 필드로 XADD. @return 이벤트 id */
    private fun publish(type: String, aggregateId: String, payload: String, id: Long = nextId.incrementAndGet()): Long {
        redis.opsForStream<String, String>().add(
            EventConsumer.MATCH_STREAM,
            mapOf("id" to id.toString(), "type" to type, "aggregateId" to aggregateId, "occurredAt" to OffsetDateTime.now().toString(), "payload" to payload),
        )
        return id
    }

    private fun fixturePayload(matchId: String, club: String, kickoff: String, home: Boolean = true) =
        """{"matchId":"$matchId","club":"$club","opponent":"Fulham","kickoff":"$kickoff","isHome":$home,"tournament":"프리미어리그","previousKickoff":null}"""

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        throw AssertionError("시간 안에 $what 되지 않았습니다")
    }

    @Test
    fun `scheduled·updated·cancelled·finished가 차례로 반영되고, 같은 이벤트는 한 번만`() {
        val kickoff = OffsetDateTime.now().plusDays(10).withNano(0)
        val id = publish("match.scheduled", "m1", fixturePayload("m1", "mancity", kickoff.toString()))
        waitUntil("일정이 생기지") { fixtures.findByMatchIdAndClub("m1", "mancity") != null }

        mvc.perform(get("/api/travel/clubs/mancity/fixtures"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].opponent").value("Fulham"))
            .andExpect(jsonPath("$[0].stadium.name").value("Etihad Stadium")) // 홈 경기 → 우리 경기장

        // 같은 id로 다시 와도(최소 한 번 전달) 두 번 반영하지 않는다 — 다른 내용이어도 무시
        publish("match.scheduled", "m1", fixturePayload("m1", "mancity", kickoff.plusDays(1).toString(), home = false), id = id)
        val moved = kickoff.plusHours(2)
        publish("match.updated", "m1", fixturePayload("m1", "mancity", moved.toString()))
        waitUntil("킥오프가 바뀌지") { fixtures.findByMatchIdAndClub("m1", "mancity")!!.kickoff.isEqual(moved) }
        assertThat(fixtures.findByMatchIdAndClub("m1", "mancity")!!.isHome).isTrue()

        publish("match.cancelled", "m1", fixturePayload("m1", "mancity", moved.toString()))
        waitUntil("취소되지") { fixtures.findByMatchIdAndClub("m1", "mancity")!!.status == FixtureStatus.CANCELLED }
        mvc.perform(get("/api/travel/clubs/mancity/fixtures")).andExpect(jsonPath("$.length()").value(0))

        publish("match.scheduled", "m1", fixturePayload("m1", "mancity", moved.toString()))
        waitUntil("다시 잡히지") { fixtures.findByMatchIdAndClub("m1", "mancity")!!.status == FixtureStatus.SCHEDULED }

        publish("match.finished", "m1", """{"matchId":"m1","clubs":["mancity"],"homeTeam":"Manchester City","awayTeam":"Fulham","homeScore":3,"awayScore":1,"kickoff":"$moved","tournament":"프리미어리그"}""")
        waitUntil("끝나지") { fixtures.findByMatchIdAndClub("m1", "mancity")!!.status == FixtureStatus.FINISHED }
        val done = fixtures.findByMatchIdAndClub("m1", "mancity")!!
        assertThat(done.homeScore).isEqualTo(3)
        assertThat(done.awayScore).isEqualTo(1)
    }

    @Test
    fun `맞대결은 팀마다 한 행, 모르는 이벤트나 모양이 잘못된 이벤트는 건너뛰고 다음 것을 처리한다`() {
        val kickoff = OffsetDateTime.now().plusDays(5).withNano(0).toString()
        publish("match.unknown", "x", "{}")
        publish("match.scheduled", "bad", """{"club":"arsenal"}""") // matchId 없음
        publish("match.scheduled", "d1", fixturePayload("d1", "arsenal", kickoff, home = true))
        publish("match.scheduled", "d1", fixturePayload("d1", "chelsea", kickoff, home = false))

        waitUntil("맞대결 두 행이 생기지") { fixtures.findAllByMatchId("d1").size == 2 }
        assertThat(fixtures.findByMatchIdAndClub("d1", "chelsea")!!.isHome).isFalse()
        assertThat(fixtures.findAll().map { it.matchId }).doesNotContain("bad")
    }
}
