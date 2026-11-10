package com.factos.travel

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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 가짜 football-svc: JWKS(공개 키)와 `/api/auth/me`만 흉내 낸다. 테스트 클래스들이 같이 쓴다 (한 JVM에 하나 —
 * 주소가 같아서 Spring 테스트 컨텍스트도 재사용된다).
 * 토큰은 여기서 football-svc 비밀 키로 직접 서명한다 — travel-svc는 공개 키로만 검증해야 한다.
 */
object FakeFootball {
    val key: ECKey = ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate()
    val otherKey: ECKey = ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate()

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

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("travel.football.base-url") { "http://localhost:${server.address.port}" }
    }

    fun jwt(memberId: Long, signWith: ECKey = key, issuer: String = "factos", expiresIn: Long = 3600): String =
        SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signWith.keyID).type(JOSEObjectType.JWT).build(),
            JWTClaimsSet.Builder().issuer(issuer).subject(memberId.toString())
                .issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(expiresIn))).build(),
        ).apply { sign(ECDSASigner(signWith)) }.serialize()

    fun meJson(
        id: Long, nickname: String, completed: Boolean = true, role: String = "MEMBER",
        suspendedUntil: String? = null, adminClub: String? = null,
    ) =
        """{"id":$id,"nickname":"$nickname","profileImageUrl":null,"provider":"KAKAO","favoriteTeam":"arsenal","signupCompleted":$completed,
           "role":"$role","adminClub":${adminClub?.let { "\"$it\"" } ?: "null"},"suspendedUntil":${suspendedUntil?.let { "\"$it\"" } ?: "null"},"suspensionReason":null}"""

    /** football-svc에 로그인한 회원 하나 → 그 쿠키 값 */
    fun login(id: Long, nickname: String, completed: Boolean = true, role: String = "MEMBER", adminClub: String? = null): String =
        jwt(id).also { meByToken[it] = meJson(id, nickname, completed, role, adminClub = adminClub) }

    /** 로그인 쿠키 + 우리 사이트 Origin */
    fun MockHttpServletRequestBuilder.as_(token: String): MockHttpServletRequestBuilder =
        cookie(Cookie("factos_session", token)).header("Origin", "http://localhost:3000")
}
