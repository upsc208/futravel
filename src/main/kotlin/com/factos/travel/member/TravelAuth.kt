package com.factos.travel.member

import com.factos.travel.football.FootballClient
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI


/**
 * 로그인 확인. **회원가입·로그인은 football-svc에서만** 한다 — 거기서 심은 쿠키(`factos_session`)가
 * 같은 도메인이라 이 서비스 요청에도 실려 온다. 그래서 한 번 로그인하면 여기서도 로그인 상태다.
 *
 * 1. 쿠키가 JWT면 football-svc 공개 키(JWKS)로 **여기서 직접** 검증 → 회원 id. 비밀값을 나눠 갖지 않는다
 * 2. 회원 사본(member_profile)에서 닉네임·권한을 읽는다 (member.* 이벤트로 맞춰진다)
 * 3. 사본이 아직 없거나(이벤트가 오기 전 / 이 서비스가 생기기 전 회원) 예전 형식 쿠키면
 *    football-svc `GET /api/auth/me`를 그 쿠키로 한 번 불러 채운다
 */
@Component
class TravelAuth(
    private val profiles: MemberProfileRepository,
    private val profileService: MemberProfileService,
    private val football: FootballClient,
    @Value("\${travel.football.base-url}") footballBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // JWKS는 받아 두고 캐시한다. 모르는 kid(키 교체)가 오면 다시 받는다
    private val jwtProcessor = DefaultJWTProcessor<SecurityContext>().apply {
        val jwks = JWKSourceBuilder.create<SecurityContext>(URI(footballBaseUrl.trimEnd('/') + "/.well-known/jwks.json").toURL())
            .retrying(true)
            .build()
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.ES256, jwks)
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
            JWTClaimsSet.Builder().issuer(ISSUER).build(),
            setOf("sub", "exp"),
        )
    }

    /** 비로그인이면 null */
    fun current(request: HttpServletRequest): MemberProfile? {
        val token = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value?.takeIf { it.isNotBlank() } ?: return null

        val memberId = if (token.count { it == '.' } == 2) {
            runCatching { jwtProcessor.process(token, null).subject.toLong() }
                .onFailure { log.debug("JWT 검증 실패: {}", it.message) }
                .getOrNull() ?: return null
        } else null // 예전 형식 — football-svc에 묻는다

        memberId?.let { id -> profiles.findById(id).orElse(null)?.let { if (!it.withdrawn) return it } }
        // 사본이 없으면(또는 예전 쿠키면) football-svc에 이 쿠키로 "나 누구야"를 묻는다
        val me = runCatching { football.me(token) }
            .onFailure { log.warn("⚠️ [Auth] football-svc /api/auth/me 실패: {}", it.message) }
            .getOrNull() ?: return null
        if (memberId != null && me.path("id").asLong() != memberId) return null
        return profileService.upsert(me)
    }

    fun require(request: HttpServletRequest): MemberProfile =
        current(request) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")

    /** 글·댓글 쓰기: 가입을 마쳤고 정지 중이 아니어야 한다 (정지는 football-svc 관리자가 건 것을 그대로 따른다) */
    fun requireWriter(request: HttpServletRequest): MemberProfile {
        val m = require(request)
        if (!m.signupCompleted) throw ResponseStatusException(HttpStatus.FORBIDDEN, "닉네임과 응원팀을 정하고 가입을 마쳐 주세요.")
        if (m.isSuspended()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "${m.suspendedUntil!!.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime().toString().replace('T', ' ').take(16)}까지 글쓰기가 정지된 상태입니다. 사유: ${m.suspensionReason ?: "-"}")
        }
        return m
    }

    companion object {
        /** football-svc MemberAuth.SESSION_COOKIE와 같은 이름 */
        const val SESSION_COOKIE = "factos_session"
        /** football-svc JwtKeys.ISSUER */
        const val ISSUER = "factos"
    }
}
