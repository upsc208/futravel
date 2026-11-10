package com.factos.travel.stadium

import com.factos.travel.club.Club
import com.factos.travel.club.NotFound
import com.factos.travel.pub.ImportStatus
import com.factos.travel.pub.OsmPubImporter
import com.factos.travel.pub.Pub
import com.factos.travel.pub.PubKind
import com.factos.travel.pub.PubRepository
import com.factos.travel.pub.PubRow
import com.factos.travel.pub.PubSource
import com.factos.travel.pub.UkArea
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class StadiumResponse(
    val club: String, val name: String, val nameKo: String, val city: String,
    val lat: Double, val lng: Double, val transit: String?,
) {
    companion object {
        fun of(s: Stadium) = StadiumResponse(s.club, s.name, s.nameKo, s.city, s.lat, s.lng, s.transit)
    }
}

data class StadiumGuide(val stadium: StadiumResponse, val pubs: List<PubRow>, val radiusMeters: Int)

data class AreaView(val id: String, val nameKo: String, val pubCount: Long)

/** 경기장에 못 갈 때: 이 팀 팬 펍(영국 어디든) + 축구 중계하는 펍 */
data class WatchSpots(val club: String, val city: String?, val fanPubs: List<PubRow>, val footballPubs: List<PubRow>)

data class PubRequest(
    @field:NotBlank @field:Size(max = 100) val name: String?,
    val club: String? = null,
    @field:NotNull @field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double?,
    @field:NotNull @field:DecimalMin("-180") @field:DecimalMax("180") val lng: Double?,
    @field:Size(max = 200) val address: String? = null,
    @field:Size(max = 500) val note: String? = null,
    @field:Size(max = 50) val city: String? = null,
    val kind: PubKind = PubKind.PUB,
    val showsFootball: Boolean = false,
    @field:Size(max = 300) val website: String? = null,
)

@RestController
@RequestMapping("/api/travel")
class StadiumController(
    private val stadiums: StadiumRepository,
    private val pubs: PubRepository,
    private val importer: OsmPubImporter,
) {
    @GetMapping("/stadiums")
    fun list(): List<StadiumResponse> = stadiums.findAllByOrderById().map(StadiumResponse::of)

    /** 경기장 가이드: 경기장 정보 + 반경 안의 펍 (기본 1.5km, 최대 5km). 그 팀 팬 펍 → 중계 → 가까운 순 */
    @GetMapping("/clubs/{club}/stadium")
    fun guide(@PathVariable club: String, @RequestParam(defaultValue = "1500") radius: Int): StadiumGuide {
        val c = Club.require(club)
        val stadium = stadiums.findByClub(c.id) ?: throw NotFound("${c.nameKo} 경기장 정보가 없습니다.")
        val r = radius.coerceIn(100, MAX_RADIUS)
        return StadiumGuide(StadiumResponse.of(stadium), pubs.findNearStadium(c.id, r.toDouble()), r)
    }

    /** 펍 정보를 받아 둔 지역 (런던 · 맨체스터 …) */
    @GetMapping("/areas")
    fun areas(): List<AreaView> {
        val counts = pubs.countByCity().associate { it.city to it.count }
        return UkArea.entries.map { AreaView(it.id, it.nameKo, counts[it.id] ?: 0) }
    }

    /** 경기장에 못 갈 때 볼 곳. [city]를 주면 그 지역만 */
    @GetMapping("/clubs/{club}/watch")
    fun watch(@PathVariable club: String, @RequestParam(required = false) city: String?): WatchSpots {
        val c = Club.require(club)
        val area = city?.takeIf { it.isNotBlank() }?.let { UkArea.of(it).id }
        return WatchSpots(c.id, area, pubs.findFanPubs(c.id, area), pubs.findFootballPubs(area))
    }

    /**
     * 내 위치 근처 펍 (기본 2km, 최대 5km). [football]이면 중계하는 곳·팀 팬 펍만.
     * [club] 팬 펍이 맨 앞에 온다
     */
    @GetMapping("/pubs/near")
    fun near(
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(defaultValue = "2000") radius: Int,
        @RequestParam(required = false) club: String?,
        @RequestParam(defaultValue = "false") football: Boolean,
    ): List<PubRow> {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "위치가 올바르지 않습니다." }
        val clubId = club?.takeIf { it.isNotBlank() }?.let { Club.require(it).id } ?: ""
        return pubs.findNear(lat, lng, radius.coerceIn(100, MAX_RADIUS).toDouble(), clubId, football)
    }

    // ── 관리자 (X-Admin-Key) ─────────────────────────────────

    @PostMapping("/admin/pubs")
    fun addPub(@Valid @RequestBody body: PubRequest): ResponseEntity<Pub> {
        body.club?.let { Club.require(it) }
        val saved = pubs.save(
            Pub(
                name = body.name!!.trim(), club = body.club, lat = body.lat!!, lng = body.lng!!, address = body.address, note = body.note,
                kind = body.kind, city = body.city?.let { UkArea.of(it).id }, showsFootball = body.showsFootball, website = body.website,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /** 직접 넣은 펍은 지우고, OSM에서 온 펍은 숨긴다 (다시 가져와도 안 보이게) */
    @DeleteMapping("/admin/pubs/{id}")
    fun deletePub(@PathVariable id: Long): ResponseEntity<Void> {
        val pub = pubs.findById(id).orElseThrow { NotFound("펍을 찾을 수 없습니다: $id") }
        if (pub.source == PubSource.OSM) pubs.save(pub.apply { hidden = true }) else pubs.delete(pub)
        return ResponseEntity.noContent().build()
    }

    /** OSM에서 펍 다시 받기 (백그라운드). [area]가 없으면 전 지역. 결과는 GET으로 본다 */
    @PostMapping("/admin/pubs/import")
    fun import(@RequestParam(required = false) area: String?): ResponseEntity<ImportStatus> {
        val areas = area?.takeIf { it.isNotBlank() }?.let { listOf(UkArea.of(it)) } ?: UkArea.entries
        val started = importer.startAsync(areas)
        return ResponseEntity.status(if (started) HttpStatus.ACCEPTED else HttpStatus.CONFLICT).body(importer.status())
    }

    @GetMapping("/admin/pubs/import")
    fun importStatus(): ImportStatus = importer.status()

    companion object {
        const val MAX_RADIUS = 5000
    }
}
