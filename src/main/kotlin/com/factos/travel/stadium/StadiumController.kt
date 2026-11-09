package com.factos.travel.stadium

import com.factos.travel.club.Club
import com.factos.travel.club.NotFound
import com.factos.travel.pub.NearbyPub
import com.factos.travel.pub.Pub
import com.factos.travel.pub.PubRepository
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

data class StadiumGuide(val stadium: StadiumResponse, val pubs: List<NearbyPub>, val radiusMeters: Int)

data class PubRequest(
    @field:NotBlank @field:Size(max = 100) val name: String?,
    val club: String? = null,
    @field:NotNull @field:DecimalMin("-90") @field:DecimalMax("90") val lat: Double?,
    @field:NotNull @field:DecimalMin("-180") @field:DecimalMax("180") val lng: Double?,
    @field:Size(max = 200) val address: String? = null,
    @field:Size(max = 500) val note: String? = null,
)

@RestController
@RequestMapping("/api/travel")
class StadiumController(
    private val stadiums: StadiumRepository,
    private val pubs: PubRepository,
) {
    @GetMapping("/stadiums")
    fun list(): List<StadiumResponse> = stadiums.findAllByOrderById().map(StadiumResponse::of)

    /** 경기장 가이드: 경기장 정보 + 반경 안의 펍 (기본 1.5km, 최대 5km) */
    @GetMapping("/clubs/{club}/stadium")
    fun guide(@PathVariable club: String, @RequestParam(defaultValue = "1500") radius: Int): StadiumGuide {
        val c = Club.require(club)
        val stadium = stadiums.findByClub(c.id) ?: throw NotFound("${c.nameKo} 경기장 정보가 없습니다.")
        val r = radius.coerceIn(100, MAX_RADIUS)
        return StadiumGuide(StadiumResponse.of(stadium), pubs.findNearStadium(c.id, r.toDouble()), r)
    }

    @PostMapping("/admin/pubs")
    fun addPub(@Valid @RequestBody body: PubRequest): ResponseEntity<Pub> {
        body.club?.let { Club.require(it) }
        val saved = pubs.save(Pub(name = body.name!!.trim(), club = body.club, lat = body.lat!!, lng = body.lng!!, address = body.address, note = body.note))
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @DeleteMapping("/admin/pubs/{id}")
    fun deletePub(@PathVariable id: Long): ResponseEntity<Void> {
        if (!pubs.existsById(id)) throw NotFound("펍을 찾을 수 없습니다: $id")
        pubs.deleteById(id)
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val MAX_RADIUS = 5000
    }
}
