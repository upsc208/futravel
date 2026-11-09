package com.factos.travel

import com.factos.travel.community.PostCommentRepository
import com.factos.travel.community.PostRepository
import com.factos.travel.event.EventConsumer
import com.factos.travel.fixture.ProcessedEventRepository
import com.factos.travel.member.MemberProfileRepository
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 커뮤니티 + 공유 로그인.
 * football-svc는 가짜 서버로 대신한다: JWKS(공개 키)와 `/api/auth/me`만 흉내 낸다.
 * 토큰은 여기서 football-svc 비밀 키로 직접 서명한다 — travel-svc는 공개 키로만 검증해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig::class)
class CommunityTest {

    companion object {
        private val key: ECKey = ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate()
        private val otherKey: ECKey = ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate()
        /** 쿠키 값 → /api/auth/me 응답 (football-svc가 아는 로그인) */
        val meByToken = ConcurrentHashMap<String, String>()
        val meCalls = AtomicLong()

        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/.well-known/jwks.json") { ex ->
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/api/auth/me") { ex ->
                meCalls.incrementAndGet()
                val token = ex.requestHeaders.getFirst("Cookie").orEmpty().substringAfter("factos_session=", "")
                val body = meByToken[token]?.toByteArray()
                if (body == null) {
                    ex.sendResponseHeaders(401, -1); ex.close()
                } else {
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
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

        fun jwt(memberId: Long, signWith: ECKey = key, issuer: String = "factos", expiresIn: Long = 3600): String =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signWith.keyID).type(JOSEObjectType.JWT).build(),
                JWTClaimsSet.Builder().issuer(issuer).subject(memberId.toString())
                    .issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(expiresIn))).build(),
            ).apply { sign(ECDSASigner(signWith)) }.serialize()

        fun meJson(id: Long, nickname: String, completed: Boolean = true, role: String = "MEMBER", suspendedUntil: String? = null) =
            """{"id":$id,"nickname":"$nickname","profileImageUrl":null,"provider":"KAKAO","favoriteTeam":"arsenal","signupCompleted":$completed,
               "role":"$role","adminClub":null,"suspendedUntil":${suspendedUntil?.let { "\"$it\"" } ?: "null"},"suspensionReason":null}"""
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var posts: PostRepository
    @Autowired private lateinit var comments: PostCommentRepository
    @Autowired private lateinit var profiles: MemberProfileRepository
    @Autowired private lateinit var processed: ProcessedEventRepository
    @Autowired private lateinit var redis: StringRedisTemplate

    private val eventIds = AtomicLong(50_000)

    @BeforeEach
    fun setUp() {
        comments.deleteAll(); posts.deleteAll(); profiles.deleteAll(); processed.deleteAll(); meByToken.clear()
    }

    private fun login(id: Long, nickname: String, completed: Boolean = true, role: String = "MEMBER"): String =
        jwt(id).also { meByToken[it] = meJson(id, nickname, completed, role) }

    private fun MockHttpServletRequestBuilder.as_(token: String) =
        cookie(Cookie("factos_session", token)).header("Origin", "http://localhost:3000")

    private fun write(token: String, title: String = "에미레이츠 직관 후기", category: String = "REVIEW") =
        post("/api/travel/posts").as_(token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"club":"arsenal","category":"$category","title":"$title","content":"북쪽 스탠드 분위기 최고"}""")

    private fun idOf(json: String) = Regex("\"id\":(\\d+)").find(json)!!.groupValues[1].toLong()

    private fun publishMember(type: String, payload: String) {
        redis.opsForStream<String, String>().add(
            EventConsumer.MEMBER_STREAM,
            mapOf("id" to eventIds.incrementAndGet().toString(), "type" to type, "aggregateId" to "m",
                "occurredAt" to OffsetDateTime.now().toString(), "payload" to payload),
        )
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(200) }
        throw AssertionError("시간 안에 $what 되지 않았습니다")
    }

    @Test
    fun `football-svc에서 로그인한 쿠키로 글을 쓴다 — 처음 보는 회원은 한 번만 물어본다`() {
        mvc.perform(get("/api/travel/posts")).andExpect(status().isOk).andExpect(jsonPath("$.totalElements").value(0))
        mvc.perform(post("/api/travel/posts").header("Origin", "http://localhost:3000").contentType(MediaType.APPLICATION_JSON)
            .content("""{"category":"FREE","title":"t","content":"c"}""")).andExpect(status().isUnauthorized)

        val token = login(1, "아스날팬")
        val before = meCalls.get()
        mvc.perform(write(token))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.author.nickname").value("아스날팬"))
            .andExpect(jsonPath("$.author.favoriteTeam").value("arsenal"))
            .andExpect(jsonPath("$.mine").value(true))
        mvc.perform(write(token, title = "두 번째 글")).andExpect(status().isCreated)
        assertThat(meCalls.get() - before).isEqualTo(1) // 두 번째부터는 사본으로

        mvc.perform(get("/api/travel/posts").param("club", "arsenal"))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].title").value("두 번째 글"))
            .andExpect(jsonPath("$.content[0].categoryLabel").value("직관 후기"))
        mvc.perform(get("/api/travel/posts").param("club", "chelsea")).andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    fun `다른 키로 서명했거나 만료된 토큰, 출처가 다른 요청은 거절한다`() {
        val forged = jwt(1, signWith = otherKey).also { meByToken[it] = meJson(1, "사칭") }
        mvc.perform(write(forged)).andExpect(status().isUnauthorized)
        mvc.perform(write(jwt(1, expiresIn = -10))).andExpect(status().isUnauthorized)
        mvc.perform(write(jwt(1, issuer = "evil"))).andExpect(status().isUnauthorized)

        val token = login(2, "팬")
        mvc.perform(post("/api/travel/posts").cookie(Cookie("factos_session", token)).header("Origin", "https://evil.example")
            .contentType(MediaType.APPLICATION_JSON).content("""{"category":"FREE","title":"t","content":"c"}"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `예전 형식 쿠키는 football-svc에 물어서 로그인으로 인정한다`() {
        val legacy = "bGVnYWN5.c2lnbmF0dXJl"
        meByToken[legacy] = meJson(3, "옛쿠키")
        mvc.perform(write(legacy)).andExpect(status().isCreated).andExpect(jsonPath("$.author.nickname").value("옛쿠키"))
    }

    @Test
    fun `가입 전이거나 정지 중이면 못 쓴다 — 정지는 football-svc 이벤트로 안다`() {
        mvc.perform(write(login(4, "팬abc", completed = false))).andExpect(status().isForbidden)

        val token = login(5, "곧정지")
        mvc.perform(write(token)).andExpect(status().isCreated)
        publishMember("member.updated", meJson(5, "곧정지", suspendedUntil = OffsetDateTime.now().plusDays(3).toString()).replace("\"id\":", "\"memberId\":"))
        waitUntil("정지가 반영") { profiles.findById(5).map { it.isSuspended() }.orElse(false) }
        mvc.perform(write(token)).andExpect(status().isForbidden).andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `닉네임 변경·탈퇴가 이벤트로 글에 반영된다`() {
        val token = login(6, "옛닉")
        mvc.perform(write(token)).andExpect(status().isCreated)

        publishMember("member.updated", meJson(6, "새닉").replace("\"id\":", "\"memberId\":"))
        waitUntil("닉네임이 바뀌") { profiles.findById(6).map { it.nickname == "새닉" }.orElse(false) }
        mvc.perform(get("/api/travel/posts")).andExpect(jsonPath("$.content[0].author.nickname").value("새닉"))

        publishMember("member.deleted", """{"memberId":6}""")
        waitUntil("탈퇴가 반영") { profiles.findById(6).map { it.withdrawn }.orElse(false) }
        mvc.perform(get("/api/travel/posts"))
            .andExpect(jsonPath("$.content[0].author.nickname").value("탈퇴한 회원"))
            .andExpect(jsonPath("$.content[0].title").value("에미레이츠 직관 후기")) // 글은 남는다
    }

    @Test
    fun `댓글을 달고 지우면 댓글 수가 따라가고, 남의 글·댓글은 못 고치지만 관리자는 지운다`() {
        val owner = login(7, "글쓴이")
        val other = login(8, "구경꾼")
        val admin = login(9, "총관", role = "ADMIN")
        val postId = idOf(mvc.perform(write(owner)).andReturn().response.contentAsString)

        val commentJson = mvc.perform(
            post("/api/travel/posts/$postId/comments").as_(other).contentType(MediaType.APPLICATION_JSON).content("""{"content":"부럽다"}""")
        ).andExpect(status().isCreated).andExpect(jsonPath("$.author.nickname").value("구경꾼")).andReturn().response.contentAsString
        mvc.perform(get("/api/travel/posts/$postId").as_(owner))
            .andExpect(jsonPath("$.commentCount").value(1))
            .andExpect(jsonPath("$.mine").value(true))
        mvc.perform(get("/api/travel/posts/$postId/comments").as_(owner))
            .andExpect(jsonPath("$.content[0].content").value("부럽다"))
            .andExpect(jsonPath("$.content[0].deletable").value(false))

        mvc.perform(put("/api/travel/posts/$postId").as_(other).contentType(MediaType.APPLICATION_JSON)
            .content("""{"category":"FREE","title":"x","content":"y"}""")).andExpect(status().isForbidden)
        mvc.perform(delete("/api/travel/posts/$postId").as_(other)).andExpect(status().isForbidden)

        mvc.perform(delete("/api/travel/comments/${idOf(commentJson)}").as_(other)).andExpect(status().isNoContent)
        mvc.perform(get("/api/travel/posts/$postId")).andExpect(jsonPath("$.commentCount").value(0))

        mvc.perform(get("/api/travel/posts/$postId").as_(admin)).andExpect(jsonPath("$.deletable").value(true))
        mvc.perform(delete("/api/travel/posts/$postId").as_(admin)).andExpect(status().isNoContent)
        assertThat(posts.existsById(postId)).isFalse()
    }
}
