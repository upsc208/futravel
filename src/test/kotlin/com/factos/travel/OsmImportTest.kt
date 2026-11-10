package com.factos.travel

import com.factos.travel.pub.OsmPub
import com.factos.travel.pub.OsmPubImporter
import com.factos.travel.pub.OverpassClient
import com.factos.travel.pub.PubKind
import com.factos.travel.pub.PubRepository
import com.factos.travel.pub.UkArea
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/** OpenStreetMap 펍 가져오기: Overpass 응답 해석 · 바쁠 때 재시도 · 다시 가져올 때 사람이 붙인 정보 지키기 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig::class)
class OsmImportTest {

    companion object {
        private val sample = OsmImportTest::class.java.getResource("/osm/sample.json")!!.readBytes()
        /** 처음 몇 번은 "바쁨(502)"을 돌려준다 — 공용 Overpass 서버가 실제로 자주 그런다 */
        val busyLeft = AtomicInteger()
        val calls = AtomicInteger()
        @Volatile var lastQuery = ""

        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/interpreter") { ex ->
                calls.incrementAndGet()
                lastQuery = URLDecoder.decode(ex.requestBody.readAllBytes().decodeToString().substringAfter("data="), Charsets.UTF_8)
                if (busyLeft.getAndDecrement() > 0) {
                    ex.sendResponseHeaders(502, -1); ex.close()
                } else {
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, sample.size.toLong())
                    ex.responseBody.use { it.write(sample) }
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun overpass(registry: DynamicPropertyRegistry) {
            FakeFootball.register(registry)
            registry.add("travel.osm.overpass-url") { "http://localhost:${server.address.port}/api/interpreter" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var importer: OsmPubImporter
    @Autowired private lateinit var pubs: PubRepository
    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        pubs.deleteAll(); busyLeft.set(0); calls.set(0)
    }

    @Test
    fun `Overpass 응답에서 이름 있고 영업 중인 펍·바만 뽑는다`() {
        val list = OverpassClient.parse(mapper.readTree(sample))
        assertThat(list.map { it.ref }).containsExactly("node/101", "way/202", "node/505") // 이름 없음·폐업 제외
        val gunners = list[0]
        assertThat(gunners.address).isEqualTo("204 Blackstock Road, N5 1EN")
        assertThat(gunners.showsFootball).isTrue()           // live_sport=yes → 중계
        assertThat(list[1].showsFootball).isFalse()          // sport=billiards는 당구대일 뿐
        assertThat(gunners.openingHours).isEqualTo("Mo-Su 12:00-23:00")
        assertThat(list[1].kind).isEqualTo(PubKind.BAR)
        assertThat(list[1].lat).isEqualTo(51.5586)           // way는 중심점
        assertThat(list[1].website).isEqualTo("http://example.com/corner")
        assertThat(list[2].website).isNull()                 // http(s)가 아닌 링크는 버린다
    }

    @Test
    fun `서버가 바쁘면 기다렸다 다시 묻고, 받은 펍이 경기장 가이드에 나온다`() {
        busyLeft.set(2)
        val result = importer.importArea(UkArea.LONDON)
        assertThat(calls.get()).isEqualTo(3)
        assertThat(lastQuery).contains("""["amenity"~"^(pub|bar)$"]""").contains("51.28,-0.51,51.7,0.33")
        assertThat(result.created).isEqualTo(3)

        mvc.perform(get("/api/travel/clubs/arsenal/stadium").param("radius", "1000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pubs.length()").value(2))
            .andExpect(jsonPath("$.pubs[0].name").value("The Gunners")) // 중계하는 곳이 먼저
            .andExpect(jsonPath("$.pubs[0].showsFootball").value(true))
            .andExpect(jsonPath("$.pubs[0].source").value("OSM"))
            .andExpect(jsonPath("$.pubs[0].city").value("London"))
        mvc.perform(get("/api/travel/areas"))
            .andExpect(jsonPath("$[0].id").value("London"))
            .andExpect(jsonPath("$[0].pubCount").value(3))
    }

    @Test
    fun `다시 가져오면 OSM 값은 덮고, 사라진 곳은 지우되 사람이 정보를 붙였거나 숨긴 곳은 남긴다`() {
        val first = OverpassClient.parse(mapper.readTree(sample))
        importer.apply(UkArea.LONDON, first)
        val byRef = { ref: String -> pubs.findAll().first { it.osmRef == ref } }

        // 사람이 붙인 정보: 505는 첼시 팬 펍, 101은 관리자가 숨김
        pubs.save(byRef("node/505").apply { club = "chelsea"; note = "홈 팬 전용" })
        pubs.save(byRef("node/101").apply { hidden = true })

        // 다음 달: 101은 이름이 바뀌었고, 202·505는 OSM에서 사라졌다
        val next = listOf(first[0].copy(name = "The Gunners Pub", showsFootball = false))
        val result = importer.apply(UkArea.LONDON, next)

        assertThat(result.updated).isEqualTo(1)
        assertThat(result.removed).isEqualTo(1) // 202만
        val left = pubs.findAll().associateBy { it.osmRef }
        assertThat(left.keys).containsExactlyInAnyOrder("node/101", "node/505")
        assertThat(left["node/101"]!!.name).isEqualTo("The Gunners Pub")
        assertThat(left["node/101"]!!.hidden).isTrue()
        assertThat(left["node/101"]!!.showsFootball).isTrue() // OSM이 꺼도 한 번 켠 중계는 유지
        assertThat(left["node/505"]!!.club).isEqualTo("chelsea")
    }

    @Test
    fun `같은 OSM id가 두 번 와도 한 행`() {
        val one = OsmPub("node/9", "Dup", PubKind.PUB, 51.5, -0.1, null, null, null, false)
        importer.apply(UkArea.LONDON, listOf(one, one))
        assertThat(pubs.count()).isEqualTo(1)
    }
}
