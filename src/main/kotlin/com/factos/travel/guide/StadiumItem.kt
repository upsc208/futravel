package com.factos.travel.guide

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.OffsetDateTime

/** 경기장 안에서 파는 것 */
enum class ItemCategory(val label: String) {
    FOOD("음식"),
    DRINK("음료·맥주"),
    GOODS("굿즈"),
    TOUR("투어·박물관"),
}

/**
 * 경기장 안 굿즈·음식 한 줄. 이런 정보는 API가 없어서 사람이 정리한다 (관리자 입력 / 회원 제보 승인).
 * 가격은 펜스(1/100 파운드). 시즌마다 바뀌므로 언제 본 가격인지([checkedOn])를 같이 둔다
 */
@Entity
@Table(name = "stadium_item")
class StadiumItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    val club: String,
    @Enumerated(EnumType.STRING) var category: ItemCategory,
    var name: String,
    @Column(name = "name_ko") var nameKo: String?,
    @Column(name = "price_pence") var pricePence: Int?,
    var note: String?,
    @Column(name = "checked_on") var checkedOn: LocalDate?,
    @Column(name = "created_at", insertable = false, updatable = false) val createdAt: OffsetDateTime? = null,
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface StadiumItemRepository : JpaRepository<StadiumItem, Long> {
    fun findAllByClubOrderByCategoryAscPricePenceAscIdAsc(club: String): List<StadiumItem>
}

enum class ReportKind { ITEM, PUB }
enum class ReportStatus { PENDING, APPROVED, REJECTED }

/**
 * 회원 제보. 승인되면 반영된다 ([GuideReportService.approve]).
 * - ITEM: 기존 항목 가격 수정([itemId]) 또는 새 항목(분류·이름)
 * - PUB: 대상 펍([pubId])에 "이 팀 팬 펍"([club]) · "축구 중계"([showsFootball]) · 메모
 */
@Entity
@Table(name = "guide_report")
class GuideReport(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Enumerated(EnumType.STRING) val kind: ReportKind,
    val club: String?,
    @Column(name = "item_id") val itemId: Long? = null,
    @Column(name = "pub_id") val pubId: Long? = null,
    @Enumerated(EnumType.STRING) val category: ItemCategory? = null,
    val name: String? = null,
    @Column(name = "price_pence") val pricePence: Int? = null,
    @Column(name = "shows_football") val showsFootball: Boolean? = null,
    val note: String? = null,
    @Column(name = "seen_on") val seenOn: LocalDate? = null,
    @Column(name = "reporter_id") val reporterId: Long,
    @Enumerated(EnumType.STRING) var status: ReportStatus = ReportStatus.PENDING,
    @Column(name = "reviewer_id") var reviewerId: Long? = null,
    @Column(name = "reviewed_at") var reviewedAt: OffsetDateTime? = null,
    @Column(name = "created_at", insertable = false, updatable = false) val createdAt: OffsetDateTime? = null,
)

interface GuideReportRepository : JpaRepository<GuideReport, Long> {
    fun findAllByStatusOrderByIdAsc(status: ReportStatus): List<GuideReport>
    fun countByReporterIdAndStatus(reporterId: Long, status: ReportStatus): Long
    fun findFirstByReporterIdOrderByIdDesc(reporterId: Long): GuideReport?
}
