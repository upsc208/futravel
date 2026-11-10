package com.factos.travel

import com.factos.travel.FakeFootball.as_
import com.factos.travel.FakeFootball.login
import com.factos.travel.FakeFootball.meByToken
import com.factos.travel.guide.GuideReportRepository
import com.factos.travel.guide.StadiumItemRepository
import com.factos.travel.member.MemberProfileRepository
import com.factos.travel.pub.PubRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

/** 경기장 안 굿즈·음식 가격 + 회원 제보 + 경기장 못 갈 때 볼 펍 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig::class)
class GuideTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun football(registry: DynamicPropertyRegistry) = FakeFootball.register(registry)
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var items: StadiumItemRepository
    @Autowired private lateinit var reports: GuideReportRepository
    @Autowired private lateinit var pubs: PubRepository
    @Autowired private lateinit var profiles: MemberProfileRepository

    private lateinit var member: String
    private lateinit var member2: String
    private lateinit var arsenalAdmin: String
    private lateinit var chelseaAdmin: String

    @BeforeEach
    fun setUp() {
        reports.deleteAll(); items.deleteAll(); pubs.deleteAll(); profiles.deleteAll(); meByToken.clear()
        member = login(21, "직관러")
        member2 = login(22, "여행러")
        arsenalAdmin = login(23, "아스날관리", role = "TEAM_ADMIN", adminClub = "arsenal")
        chelseaAdmin = login(24, "첼시관리", role = "TEAM_ADMIN", adminClub = "chelsea")
    }

    private fun json(s: String) = s
    private fun idOf(body: String) = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()

    private fun addItem(token: String, body: String = """{"category":"FOOD","name":"Chicken Balti Pie","nameKo":"치킨 발티 파이","pricePence":480,"checkedOn":"2026-10-04"}""") =
        mvc.perform(post("/api/travel/clubs/arsenal/items").as_(token).contentType(MediaType.APPLICATION_JSON).content(json(body)))

    private fun report(token: String, body: String) =
        mvc.perform(post("/api/travel/reports").as_(token).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun addPub(name: String, lat: Double, lng: Double, extra: String = "") =
        idOf(
            mvc.perform(
                post("/api/travel/admin/pubs").header("X-Admin-Key", "test-admin-key").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"$name","lat":$lat,"lng":$lng,"city":"London"$extra}""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        )

    @Test
    fun `경기장 항목은 그 팀 관리자만 고치고, 누구나 원화 환산용 환율과 함께 본다`() {
        addItem(member).andExpect(status().isForbidden)
        addItem(chelseaAdmin).andExpect(status().isForbidden)
        val id = idOf(addItem(arsenalAdmin).andExpect(status().isCreated).andReturn().response.contentAsString)
        addItem(arsenalAdmin, """{"category":"GOODS","name":"Home Shirt","pricePence":-1}""").andExpect(status().isBadRequest)
        addItem(arsenalAdmin, """{"category":"SOUVENIR","name":"x"}""").andExpect(status().isBadRequest)

        mvc.perform(get("/api/travel/clubs/arsenal/items"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gbpKrw").value(1800.0))
            .andExpect(jsonPath("$.canManage").value(false))
            .andExpect(jsonPath("$.items[0].nameKo").value("치킨 발티 파이"))
            .andExpect(jsonPath("$.items[0].pricePence").value(480))
            .andExpect(jsonPath("$.items[0].categoryLabel").value("음식"))
        mvc.perform(get("/api/travel/clubs/arsenal/items").as_(arsenalAdmin)).andExpect(jsonPath("$.canManage").value(true))
        mvc.perform(get("/api/travel/clubs/chelsea/items")).andExpect(jsonPath("$.items", hasSize<Any>(0)))

        mvc.perform(put("/api/travel/items/$id").as_(chelseaAdmin).contentType(MediaType.APPLICATION_JSON)
            .content("""{"category":"FOOD","name":"x"}""")).andExpect(status().isForbidden)
        mvc.perform(delete("/api/travel/items/$id").as_(arsenalAdmin)).andExpect(status().isNoContent)
    }

    @Test
    fun `회원 가격 제보 → 그 팀 관리자가 승인하면 가격과 확인 날짜가 바뀐다`() {
        val itemId = idOf(addItem(arsenalAdmin).andReturn().response.contentAsString)

        report(member, """{"kind":"ITEM","itemId":$itemId,"pricePence":520,"seenOn":"2026-11-01","note":"카드만 받아요"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.target").value("치킨 발티 파이"))
        report(member, """{"kind":"ITEM","itemId":$itemId,"pricePence":530}""").andExpect(status().isBadRequest) // 30초에 한 번
        report(member2, """{"kind":"ITEM","itemId":$itemId}""").andExpect(status().isBadRequest)                // 가격 없음

        mvc.perform(get("/api/travel/reports").as_(member)).andExpect(status().isForbidden)
        mvc.perform(get("/api/travel/reports").as_(chelseaAdmin)).andExpect(jsonPath("$", hasSize<Any>(0)))
        val reportId = idOf(
            mvc.perform(get("/api/travel/reports").as_(arsenalAdmin))
                .andExpect(jsonPath("$", hasSize<Any>(1)))
                .andExpect(jsonPath("$[0].reporter").value("직관러"))
                .andReturn().response.contentAsString,
        )
        mvc.perform(post("/api/travel/reports/$reportId/approve").as_(chelseaAdmin)).andExpect(status().isForbidden)
        mvc.perform(post("/api/travel/reports/$reportId/approve").as_(arsenalAdmin))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("APPROVED"))
        mvc.perform(post("/api/travel/reports/$reportId/approve").as_(arsenalAdmin)).andExpect(status().isConflict)

        val item = items.findById(itemId).get()
        assertThat(item.pricePence).isEqualTo(520)
        assertThat(item.checkedOn).isEqualTo(LocalDate.parse("2026-11-01"))
        assertThat(item.note).isEqualTo("카드만 받아요")
    }

    @Test
    fun `새 항목 제보는 거절하면 아무것도 생기지 않고, 승인하면 생긴다`() {
        val r1 = idOf(report(member, """{"kind":"ITEM","club":"arsenal","category":"DRINK","name":"Carling pint","pricePence":650}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val r2 = idOf(report(member2, """{"kind":"ITEM","club":"arsenal","category":"GOODS","name":"Scarf","pricePence":1800}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        mvc.perform(post("/api/travel/reports/$r1/reject").as_(arsenalAdmin)).andExpect(jsonPath("$.status").value("REJECTED"))
        mvc.perform(post("/api/travel/reports/$r2/approve").as_(arsenalAdmin)).andExpect(status().isOk)
        assertThat(items.findAll().map { it.name }).containsExactly("Scarf")
    }

    @Test
    fun `펍 제보 "아스날 팬 펍이에요" → 승인되면 경기장 못 갈 때 목록과 내 근처 검색 맨 앞에 나온다`() {
        val pubId = addPub("The Tollington", 51.5600, -0.1150)
        addPub("Sports Bar London", 51.5100, -0.1300, ""","showsFootball":true""")
        addPub("Quiet Inn", 51.5605, -0.1155)

        report(member, """{"kind":"PUB","pubId":$pubId}""").andExpect(status().isBadRequest) // 알려 줄 내용이 없다
        val reportId = idOf(report(member2, """{"kind":"PUB","pubId":$pubId,"club":"arsenal","showsFootball":true,"note":"원정 팬 출입 금지"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        mvc.perform(post("/api/travel/reports/$reportId/approve").as_(arsenalAdmin)).andExpect(status().isOk)

        mvc.perform(get("/api/travel/clubs/arsenal/watch"))
            .andExpect(jsonPath("$.fanPubs", hasSize<Any>(1)))
            .andExpect(jsonPath("$.fanPubs[0].name").value("The Tollington"))
            .andExpect(jsonPath("$.fanPubs[0].note").value("원정 팬 출입 금지"))
            .andExpect(jsonPath("$.footballPubs", hasSize<Any>(1)))
            .andExpect(jsonPath("$.footballPubs[0].name").value("Sports Bar London"))
        mvc.perform(get("/api/travel/clubs/chelsea/watch"))
            .andExpect(jsonPath("$.fanPubs", hasSize<Any>(0)))
            .andExpect(jsonPath("$.footballPubs", hasSize<Any>(1)))
        mvc.perform(get("/api/travel/clubs/arsenal/watch").param("city", "Manchester")).andExpect(jsonPath("$.footballPubs", hasSize<Any>(0)))

        // 내 근처: Quiet Inn이 더 가깝지만 아스날 팬 펍이 먼저. 중계만 고르면 Quiet Inn은 빠진다
        mvc.perform(get("/api/travel/pubs/near").param("lat", "51.5606").param("lng", "-0.1156").param("club", "arsenal"))
            .andExpect(jsonPath("$[0].name").value("The Tollington"))
            .andExpect(jsonPath("$[1].name").value("Quiet Inn"))
            .andExpect(jsonPath("$[0].distanceMeters").isNumber)
        mvc.perform(get("/api/travel/pubs/near").param("lat", "51.5606").param("lng", "-0.1156").param("football", "true"))
            .andExpect(jsonPath("$", hasSize<Any>(1)))
        mvc.perform(get("/api/travel/pubs/near").param("lat", "95").param("lng", "0")).andExpect(status().isBadRequest)
    }

    @Test
    fun `중계 펍이 아무리 많아도 팬 펍이 밀려나지 않는다`() {
        repeat(5) { addPub("Sports $it", 51.5 + it * 0.001, -0.12, ""","showsFootball":true""") }
        addPub("Zz Gooners", 51.56, -0.11, ""","club":"arsenal"""")
        assertThat(pubs.findFanPubs("arsenal", null, limit = 1).map { it.name }).containsExactly("Zz Gooners")
        assertThat(pubs.findFootballPubs("London", limit = 2)).hasSize(2)
        mvc.perform(get("/api/travel/clubs/arsenal/watch"))
            .andExpect(jsonPath("$.fanPubs", hasSize<Any>(1)))
            .andExpect(jsonPath("$.footballPubs", hasSize<Any>(5)))
    }

    @Test
    fun `다른 팀 팬 펍은 팀 관리자가 못 건드리고, 숨기기는 전체 관리자만`() {
        val pubId = addPub("Arsenal Pub", 51.56, -0.11, ""","club":"arsenal"""")
        val body = { s: String -> put("/api/travel/pubs/$pubId").contentType(MediaType.APPLICATION_JSON).content(s) }
        mvc.perform(body("""{"club":"chelsea"}""").as_(chelseaAdmin)).andExpect(status().isForbidden)
        mvc.perform(body("""{"club":"arsenal","hidden":true}""").as_(arsenalAdmin)).andExpect(status().isForbidden)
        mvc.perform(body("""{"club":"arsenal","showsFootball":true,"note":"경기 2시간 전엔 꽉 차요"}""").as_(arsenalAdmin))
            .andExpect(status().isOk).andExpect(jsonPath("$.showsFootball").value(true))
        val admin = login(25, "총관", role = "ADMIN")
        mvc.perform(body("""{"club":"arsenal","hidden":true}""").as_(admin)).andExpect(status().isOk)
        mvc.perform(get("/api/travel/clubs/arsenal/watch")).andExpect(jsonPath("$.fanPubs", hasSize<Any>(0)))
    }
}
