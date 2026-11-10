package com.factos.travel.guide

import com.factos.travel.club.Club
import com.factos.travel.club.NotFound
import com.factos.travel.member.MemberProfile
import com.factos.travel.member.MemberProfileRepository
import com.factos.travel.member.TravelAuth
import com.factos.travel.pub.Pub
import com.factos.travel.pub.PubRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

data class ItemView(
    val id: Long, val category: ItemCategory, val categoryLabel: String, val name: String, val nameKo: String?,
    val pricePence: Int?, val note: String?, val checkedOn: LocalDate?,
)

data class ItemsResponse(
    val club: String,
    val items: List<ItemView>,
    /** 1파운드 = 몇 원 (운영자가 정한 값). null이면 원화 표시를 하지 않는다 */
    val gbpKrw: Double?,
    /** 보는 사람이 이 경기장 항목을 고칠 수 있는지 (전체 관리자 · 그 팀 관리자) */
    val canManage: Boolean,
)

data class ItemRequest(
    val category: String?, val name: String?, val nameKo: String? = null,
    val pricePence: Int? = null, val note: String? = null, val checkedOn: LocalDate? = null,
)

data class PubCurateRequest(val club: String? = null, val showsFootball: Boolean? = null, val note: String? = null, val hidden: Boolean? = null)

data class ReportRequest(
    val kind: String?,
    val club: String? = null,
    val itemId: Long? = null,
    val pubId: Long? = null,
    val category: String? = null,
    val name: String? = null,
    val pricePence: Int? = null,
    val showsFootball: Boolean? = null,
    val note: String? = null,
    val seenOn: LocalDate? = null,
)

data class ReportView(
    val id: Long, val kind: ReportKind, val club: String?, val status: ReportStatus,
    /** 무엇에 대한 제보인지 사람이 읽을 이름 (기존 항목·펍 이름, 새 항목이면 제보한 이름) */
    val target: String,
    val itemId: Long?, val pubId: Long?, val category: ItemCategory?, val name: String?,
    val pricePence: Int?, val showsFootball: Boolean?, val note: String?, val seenOn: LocalDate?,
    val reporter: String, val createdAt: OffsetDateTime?,
)

/**
 * 경기장 안 굿즈·음식(가격) + 회원 제보.
 * 읽기는 누구나. 항목 고치기·제보 처리는 관리자(전체 / 그 팀), 제보는 가입을 마친 회원.
 */
@RestController
@RequestMapping("/api/travel")
class GuideController(
    private val items: StadiumItemRepository,
    private val pubs: PubRepository,
    private val reports: GuideReportService,
    private val auth: TravelAuth,
    @Value("\${travel.fx.gbp-krw:}") private val gbpKrw: String,
) {
    @GetMapping("/clubs/{club}/items")
    fun list(@PathVariable club: String, request: HttpServletRequest): ItemsResponse {
        val c = Club.require(club)
        val viewer = auth.current(request)
        return ItemsResponse(
            c.id, items.findAllByClubOrderByCategoryAscPricePenceAscIdAsc(c.id).map(::view),
            gbpKrw.toDoubleOrNull()?.takeIf { it > 0 }, viewer?.canModerate(c.id) == true,
        )
    }

    @PostMapping("/clubs/{club}/items")
    fun add(@PathVariable club: String, @RequestBody body: ItemRequest, request: HttpServletRequest): ResponseEntity<ItemView> {
        val c = Club.require(club)
        requireManager(request, c.id)
        val v = GuideValidation.item(body)
        val saved = items.save(StadiumItem(club = c.id, category = v.category, name = v.name, nameKo = v.nameKo, pricePence = v.pricePence, note = v.note, checkedOn = v.checkedOn))
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved))
    }

    @PutMapping("/items/{id}")
    fun update(@PathVariable id: Long, @RequestBody body: ItemRequest, request: HttpServletRequest): ItemView {
        val item = items.findById(id).orElseThrow { NotFound("항목을 찾을 수 없습니다.") }
        requireManager(request, item.club)
        val v = GuideValidation.item(body)
        item.category = v.category; item.name = v.name; item.nameKo = v.nameKo
        item.pricePence = v.pricePence; item.note = v.note; item.checkedOn = v.checkedOn
        item.updatedAt = OffsetDateTime.now()
        return view(items.save(item))
    }

    @DeleteMapping("/items/{id}")
    fun delete(@PathVariable id: Long, request: HttpServletRequest): ResponseEntity<Void> {
        val item = items.findById(id).orElseThrow { NotFound("항목을 찾을 수 없습니다.") }
        requireManager(request, item.club)
        items.delete(item)
        return ResponseEntity.noContent().build()
    }

    /**
     * 펍에 사람이 아는 정보를 붙인다: 이 팀 팬 펍 · 축구 중계 · 한국어 메모 · 숨기기.
     * 팀 관리자는 자기 팀으로 표시하거나 자기 팀 표시를 뗄 수 있고, 숨기기는 전체 관리자만
     */
    @PutMapping("/pubs/{id}")
    fun curate(@PathVariable id: Long, @RequestBody body: PubCurateRequest, request: HttpServletRequest): Pub {
        val me = auth.require(request)
        val pub = pubs.findById(id).orElseThrow { NotFound("펍을 찾을 수 없습니다.") }
        val newClub = body.club?.takeIf { it.isNotBlank() }?.let { Club.require(it).id }
        if (!GuideAccess.canTouchPub(me, pub.club, newClub) || (body.hidden != null && !me.isAdmin)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 고칠 수 있습니다.")
        pub.club = newClub
        body.showsFootball?.let { pub.showsFootball = it }
        body.note?.let { pub.note = GuideValidation.note(it) }
        body.hidden?.let { pub.hidden = it }
        pub.updatedAt = OffsetDateTime.now()
        return pubs.save(pub)
    }

    // ── 제보 ────────────────────────────────────────────────

    @PostMapping("/reports")
    fun report(@RequestBody body: ReportRequest, request: HttpServletRequest): ResponseEntity<ReportView> =
        ResponseEntity.status(HttpStatus.CREATED).body(reports.submit(auth.requireWriter(request), body))

    /** 처리할 제보 (관리자: 전부 · 팀 관리자: 그 팀 것) */
    @GetMapping("/reports")
    fun pending(request: HttpServletRequest): List<ReportView> {
        val me = auth.require(request)
        if (!GuideAccess.isManager(me)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 볼 수 있습니다.")
        return reports.pendingFor(me)
    }

    @PostMapping("/reports/{id}/approve")
    fun approve(@PathVariable id: Long, request: HttpServletRequest): ReportView = reports.review(auth.require(request), id, approve = true)

    @PostMapping("/reports/{id}/reject")
    fun reject(@PathVariable id: Long, request: HttpServletRequest): ReportView = reports.review(auth.require(request), id, approve = false)

    private fun requireManager(request: HttpServletRequest, club: String): MemberProfile {
        val me = auth.require(request)
        if (!me.canModerate(club)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 고칠 수 있습니다.")
        return me
    }

    private fun view(i: StadiumItem) = ItemView(i.id!!, i.category, i.category.label, i.name, i.nameKo, i.pricePence, i.note, i.checkedOn)
}

/** 펍·제보 권한. 전체 관리자는 전부, 팀 관리자는 "팀 없음"이거나 자기 팀인 것만 */
object GuideAccess {
    private fun MemberProfile.ownsOrFree(club: String?) = club == null || canModerate(club)

    fun isManager(me: MemberProfile) = me.isAdmin || me.role == "TEAM_ADMIN"

    /** 펍의 지금 팀 [current]에서 [next]로 — 다른 팀 팬 펍을 뺏거나 넘길 수 없다 */
    fun canTouchPub(me: MemberProfile, current: String?, next: String?) =
        me.isAdmin || (me.role == "TEAM_ADMIN" && me.ownsOrFree(current) && me.ownsOrFree(next))

    fun canReview(me: MemberProfile, r: GuideReport, pubClub: String?) = when (r.kind) {
        ReportKind.ITEM -> r.club != null && me.canModerate(r.club)
        ReportKind.PUB -> canTouchPub(me, pubClub, r.club)
    }
}

/** 입력 검사 (항목·제보가 같이 쓴다) */
object GuideValidation {
    const val MAX_NAME = 100
    const val MAX_NOTE = 300
    const val MAX_PRICE = 100_000 // £1,000

    data class Item(val category: ItemCategory, val name: String, val nameKo: String?, val pricePence: Int?, val note: String?, val checkedOn: LocalDate?)

    fun item(body: ItemRequest) = Item(
        category(body.category ?: throw IllegalArgumentException("분류를 골라 주세요.")),
        name(body.name),
        body.nameKo?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_NAME) throw IllegalArgumentException("한국어 이름은 ${MAX_NAME}자까지입니다.") },
        price(body.pricePence),
        body.note?.let(::note),
        date(body.checkedOn),
    )

    fun category(raw: String) = ItemCategory.entries.find { it.name == raw.trim().uppercase() }
        ?: throw IllegalArgumentException("분류는 ${ItemCategory.entries.joinToString { it.name }} 중 하나입니다.")

    fun name(raw: String?): String {
        val n = raw?.trim().orEmpty()
        if (n.isEmpty()) throw IllegalArgumentException("이름을 입력해 주세요.")
        if (n.length > MAX_NAME) throw IllegalArgumentException("이름은 ${MAX_NAME}자까지입니다.")
        return n
    }

    fun price(p: Int?): Int? = p?.also { if (it !in 0..MAX_PRICE) throw IllegalArgumentException("가격이 올바르지 않습니다.") }

    fun note(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }?.also {
        if (it.length > MAX_NOTE) throw IllegalArgumentException("메모는 ${MAX_NOTE}자까지입니다.")
    }

    fun date(d: LocalDate?): LocalDate? = d?.also {
        if (it.isAfter(LocalDate.now(ZoneId.of("Europe/London")).plusDays(1))) throw IllegalArgumentException("날짜가 미래입니다.")
    }
}

@Service
class GuideReportService(
    private val reports: GuideReportRepository,
    private val items: StadiumItemRepository,
    private val pubs: PubRepository,
    private val profiles: MemberProfileRepository,
) {
    @Transactional
    fun submit(me: MemberProfile, body: ReportRequest): ReportView {
        reports.findFirstByReporterIdOrderByIdDesc(me.memberId)?.createdAt?.let {
            if (Duration.between(it, OffsetDateTime.now()) < COOLDOWN) throw IllegalArgumentException("제보는 ${COOLDOWN.seconds}초에 한 번 할 수 있습니다.")
        }
        if (reports.countByReporterIdAndStatus(me.memberId, ReportStatus.PENDING) >= MAX_PENDING) {
            throw IllegalArgumentException("처리를 기다리는 제보가 너무 많습니다. 조금 뒤에 다시 해 주세요.")
        }
        val kind = ReportKind.entries.find { it.name == body.kind?.trim()?.uppercase() } ?: throw IllegalArgumentException("제보 종류(ITEM, PUB)를 골라 주세요.")
        val note = body.note?.let(GuideValidation::note)
        val seenOn = GuideValidation.date(body.seenOn)
        val report = when (kind) {
            ReportKind.ITEM -> {
                val price = GuideValidation.price(body.pricePence)
                if (body.itemId != null) {
                    val item = items.findById(body.itemId).orElseThrow { NotFound("항목을 찾을 수 없습니다.") }
                    if (price == null) throw IllegalArgumentException("본 가격을 입력해 주세요.")
                    GuideReport(kind = kind, club = item.club, itemId = item.id, pricePence = price, note = note, seenOn = seenOn, reporterId = me.memberId)
                } else {
                    val club = Club.require(body.club ?: throw IllegalArgumentException("어느 경기장인지 골라 주세요.")).id
                    val category = GuideValidation.category(body.category ?: throw IllegalArgumentException("분류를 골라 주세요."))
                    GuideReport(kind = kind, club = club, category = category, name = GuideValidation.name(body.name), pricePence = price, note = note, seenOn = seenOn, reporterId = me.memberId)
                }
            }
            ReportKind.PUB -> {
                val pub = pubs.findById(body.pubId ?: throw IllegalArgumentException("어느 펍인지 골라 주세요.")).orElseThrow { NotFound("펍을 찾을 수 없습니다.") }
                val club = body.club?.takeIf { it.isNotBlank() }?.let { Club.require(it).id }
                if (club == null && body.showsFootball == null && note == null) throw IllegalArgumentException("알려 줄 내용을 골라 주세요.")
                GuideReport(kind = kind, club = club, pubId = pub.id, showsFootball = body.showsFootball, note = note, seenOn = seenOn, reporterId = me.memberId)
            }
        }
        return view(reports.save(report), mapOf(me.memberId to me))
    }

    @Transactional(readOnly = true)
    fun pendingFor(me: MemberProfile): List<ReportView> {
        val list = reports.findAllByStatusOrderByIdAsc(ReportStatus.PENDING).filter { canReview(me, it) }
        val reporters = profiles.findAllById(list.map { it.reporterId }.toSet()).associateBy { it.memberId }
        return list.map { view(it, reporters) }
    }

    /** 승인하면 제보 내용을 반영한다 (한 트랜잭션). 이미 처리된 제보는 다시 못 한다 */
    @Transactional
    fun review(me: MemberProfile, id: Long, approve: Boolean): ReportView {
        val r = reports.findById(id).orElseThrow { NotFound("제보를 찾을 수 없습니다.") }
        if (!canReview(me, r)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 처리할 수 있습니다.")
        if (r.status != ReportStatus.PENDING) throw ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 제보입니다.")
        if (approve) apply(r)
        r.status = if (approve) ReportStatus.APPROVED else ReportStatus.REJECTED
        r.reviewerId = me.memberId
        r.reviewedAt = OffsetDateTime.now()
        return view(reports.save(r), profiles.findAllById(listOf(r.reporterId)).associateBy { it.memberId })
    }

    private fun apply(r: GuideReport) {
        val now = OffsetDateTime.now()
        val checked = r.seenOn ?: LocalDate.now(ZoneId.of("Europe/London"))
        when (r.kind) {
            ReportKind.ITEM -> if (r.itemId != null) {
                val item = items.findById(r.itemId!!).orElseThrow { NotFound("항목이 지워졌습니다.") }
                item.pricePence = r.pricePence
                item.checkedOn = checked
                r.note?.let { item.note = it }
                item.updatedAt = now
            } else {
                items.save(StadiumItem(club = r.club!!, category = r.category!!, name = r.name!!, nameKo = null, pricePence = r.pricePence, note = r.note, checkedOn = checked))
            }
            ReportKind.PUB -> {
                val pub = pubs.findById(r.pubId!!).orElseThrow { NotFound("펍이 지워졌습니다.") }
                r.club?.let { pub.club = it }
                r.showsFootball?.let { pub.showsFootball = it }
                r.note?.let { pub.note = it }
                pub.updatedAt = now
            }
        }
    }

    private fun canReview(me: MemberProfile, r: GuideReport) =
        GuideAccess.canReview(me, r, r.pubId?.let { id -> pubs.findById(id).map { it.club }.orElse(null) })

    private fun view(r: GuideReport, reporters: Map<Long, MemberProfile>): ReportView {
        val target = when {
            r.itemId != null -> items.findById(r.itemId!!).map { it.nameKo ?: it.name }.orElse("(지워진 항목)")
            r.pubId != null -> pubs.findById(r.pubId!!).map { it.name }.orElse("(지워진 펍)")
            else -> r.name ?: "-"
        }
        val reporter = reporters[r.reporterId]?.displayName ?: MemberProfile.WITHDRAWN
        return ReportView(
            r.id!!, r.kind, r.club, r.status, target, r.itemId, r.pubId, r.category, r.name,
            r.pricePence, r.showsFootball, r.note, r.seenOn, reporter, r.createdAt,
        )
    }

    companion object {
        val COOLDOWN: Duration = Duration.ofSeconds(30)
        const val MAX_PENDING = 20
    }
}
