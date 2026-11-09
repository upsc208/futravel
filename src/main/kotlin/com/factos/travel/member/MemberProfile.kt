package com.factos.travel.member

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 회원 사본 — 작성자 표시(닉네임·사진·응원팀)와 권한(가입 완료·정지·관리자) 확인용.
 * 원본은 football-svc다. 회원가입·로그인은 거기서만 한다 (유저는 서비스끼리 공유).
 */
@Entity
@Table(name = "member_profile")
class MemberProfile(
    @Id @Column(name = "member_id") val memberId: Long,
    var nickname: String,
    @Column(name = "profile_image_url") var profileImageUrl: String?,
    @Column(name = "favorite_team") var favoriteTeam: String?,
    @Column(name = "signup_completed") var signupCompleted: Boolean,
    var role: String,
    @Column(name = "admin_club") var adminClub: String?,
    @Column(name = "suspended_until") var suspendedUntil: OffsetDateTime?,
    @Column(name = "suspension_reason") var suspensionReason: String?,
    var withdrawn: Boolean = false,
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
) {
    fun isSuspended(now: OffsetDateTime = OffsetDateTime.now()) = suspendedUntil?.isAfter(now) == true
    val isAdmin get() = role == "ADMIN"
    /** 이 팀 글을 관리할 수 있는지 (전체 관리자, 또는 그 팀 관리자) */
    fun canModerate(club: String?) = isAdmin || (role == "TEAM_ADMIN" && club != null && club == adminClub)
    val displayName get() = if (withdrawn) WITHDRAWN else nickname

    companion object {
        const val WITHDRAWN = "탈퇴한 회원"
    }
}

interface MemberProfileRepository : JpaRepository<MemberProfile, Long>

/** football-svc 회원 정보를 사본에 덮어쓴다. 이벤트(member.updated)와 /api/auth/me 응답이 같은 필드 이름을 쓴다 */
@Service
class MemberProfileService(private val profiles: MemberProfileRepository) {

    @Transactional
    fun upsert(node: JsonNode): MemberProfile {
        val id = (node.path("memberId").takeIf { !it.isMissingNode } ?: node.path("id")).asLong()
        require(id > 0) { "회원 id가 없습니다" }
        val p = profiles.findById(id).orElse(null)
            ?: MemberProfile(id, "", null, null, false, "MEMBER", null, null, null)
        p.nickname = node.path("nickname").asText()
        p.profileImageUrl = node.path("profileImageUrl").textOrNull()
        p.favoriteTeam = node.path("favoriteTeam").textOrNull()
        p.signupCompleted = node.path("signupCompleted").asBoolean()
        p.role = node.path("role").textOrNull() ?: "MEMBER"
        p.adminClub = node.path("adminClub").textOrNull()
        p.suspendedUntil = node.path("suspendedUntil").textOrNull()?.let(OffsetDateTime::parse)
        p.suspensionReason = node.path("suspensionReason").textOrNull()
        p.withdrawn = false
        p.updatedAt = OffsetDateTime.now()
        return profiles.save(p)
    }

    /** 탈퇴: 사본을 지우지 않고 표시만 바꾼다 (글은 남는다) */
    @Transactional
    fun withdraw(memberId: Long) {
        profiles.findById(memberId).ifPresent {
            it.withdrawn = true
            it.updatedAt = OffsetDateTime.now()
        }
    }

    private fun JsonNode.textOrNull(): String? = if (isMissingNode || isNull) null else asText().takeIf { it.isNotBlank() }
}
