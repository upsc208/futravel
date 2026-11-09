package com.factos.travel

import com.factos.travel.pub.PubRepository
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 경기장 가이드: Flyway 시드 경기장 + PostGIS 반경 검색으로 가까운 펍 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig::class)
class StadiumGuideTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var pubs: PubRepository

    @BeforeEach
    fun setUp() = pubs.deleteAll()

    private fun addPub(name: String, lat: Double, lng: Double, key: String? = "test-admin-key") =
        mvc.perform(
            post("/api/travel/admin/pubs").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","club":"arsenal","lat":$lat,"lng":$lng}""")
                .apply { if (key != null) header("X-Admin-Key", key) }
        )

    @Test
    fun `Big6 경기장 6곳이 시드돼 있다`() {
        mvc.perform(get("/api/travel/stadiums"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(6))
            .andExpect(jsonPath("$[0].club").value("arsenal"))
            .andExpect(jsonPath("$[0].nameKo").value("에미레이츠 스타디움"))
    }

    @Test
    fun `반경 안의 펍만 가까운 순으로, 거리는 미터로 준다`() {
        addPub("가까운 펍", 51.5560, -0.1080).andExpect(status().isCreated)   // 에미레이츠에서 약 120m
        addPub("조금 먼 펍", 51.5586, -0.1059).andExpect(status().isCreated)  // 약 450m
        addPub("첼시 근처 펍", 51.4820, -0.1900).andExpect(status().isCreated) // 9km 넘게

        mvc.perform(get("/api/travel/clubs/arsenal/stadium").param("radius", "1000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stadium.name").value("Emirates Stadium"))
            .andExpect(jsonPath("$.radiusMeters").value(1000))
            .andExpect(jsonPath("$.pubs.length()").value(2))
            .andExpect(jsonPath("$.pubs[0].name").value("가까운 펍"))
            .andExpect(jsonPath("$.pubs[0].distanceMeters").value(closeTo(125.0, 30.0)))
            .andExpect(jsonPath("$.pubs[1].name").value("조금 먼 펍"))
    }

    @Test
    fun `펍 추가는 관리자 키가 있어야 하고, 모르는 팀은 404`() {
        addPub("펍", 51.5, -0.1, key = null).andExpect(status().isForbidden)
        addPub("펍", 51.5, -0.1, key = "wrong").andExpect(status().isForbidden)
        mvc.perform(get("/api/travel/clubs/barcelona/stadium")).andExpect(status().isNotFound)
        mvc.perform(
            post("/api/travel/admin/pubs").contentType(MediaType.APPLICATION_JSON).header("X-Admin-Key", "test-admin-key")
                .content("""{"name":"위도 이상","lat":91,"lng":0}""")
        ).andExpect(status().isBadRequest)
    }
}
