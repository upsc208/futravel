package com.factos.travel.pub

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 펍을 받아 오는 지역. 경기장이 있는 도시 + 한국 여행객이 많이 들르는 곳.
 * 범위는 경계 상자(남, 서, 북, 동) — 행정 경계 이름보다 결과가 일정하다
 */
enum class UkArea(val id: String, val nameKo: String, val south: Double, val west: Double, val north: Double, val east: Double) {
    LONDON("London", "런던", 51.28, -0.51, 51.70, 0.33),
    MANCHESTER("Manchester", "맨체스터", 53.34, -2.73, 53.69, -1.91),
    LIVERPOOL("Liverpool", "리버풀", 53.32, -3.02, 53.48, -2.81),
    EDINBURGH("Edinburgh", "에든버러", 55.88, -3.35, 56.00, -3.07);

    companion object {
        fun of(id: String) = entries.find { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
            ?: throw IllegalArgumentException("지역은 ${entries.joinToString { it.id }} 중 하나입니다.")
    }
}

/** OSM 펍 한 곳 (Overpass 응답에서 뽑은 값) */
data class OsmPub(
    val ref: String, val name: String, val kind: PubKind, val lat: Double, val lng: Double,
    val address: String?, val website: String?, val openingHours: String?, val showsFootball: Boolean,
)

/**
 * OpenStreetMap Overpass API 클라이언트. 공용 서버라 자주 "바쁨(429/502/504)"을 돌려준다 →
 * 간격을 두 배씩 늘려 다시 시도한다. 사용자 요청 때 부르지 않고, 받아 둔 것을 DB에서 검색한다.
 */
@Component
class OverpassClient(
    @Value("\${travel.osm.overpass-url:https://overpass-api.de/api/interpreter}") private val url: String,
    @Value("\${travel.osm.user-agent:factos-travel/1.0}") userAgent: String,
    @Value("\${travel.osm.retry-base-ms:5000}") private val retryBaseMs: Long,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder()
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(200))
        })
        .defaultHeader("User-Agent", userAgent)
        .build()

    fun pubs(area: UkArea): List<OsmPub> {
        val bbox = "${area.south},${area.west},${area.north},${area.east}"
        val query = """[out:json][timeout:180];nwr["amenity"~"^(pub|bar)$"]["name"]($bbox);out center tags;"""
        return parse(fetch(query))
    }

    private fun fetch(query: String): JsonNode {
        var wait = retryBaseMs
        repeat(MAX_TRIES) { attempt ->
            try {
                val body = client.post().uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(LinkedMultiValueMap<String, String>().apply { add("data", query) })
                    .retrieve().body(String::class.java)
                    ?: throw IllegalStateException("빈 응답")
                return mapper.readTree(body)
            } catch (e: RestClientResponseException) {
                if (e.statusCode.value() !in BUSY || attempt == MAX_TRIES - 1) throw e
                log.info("⏳ [OSM] Overpass가 바쁩니다({}). {}초 뒤 다시 ({}/{})", e.statusCode.value(), wait / 1000, attempt + 1, MAX_TRIES)
            }
            Thread.sleep(wait)
            wait *= 2
        }
        error("unreachable")
    }

    companion object {
        private const val MAX_TRIES = 5
        private val BUSY = setOf(429, 502, 503, 504)

        /** Overpass `out center tags` 응답 → 펍 목록. 이름 없는 곳·닫은 곳은 뺀다 */
        fun parse(root: JsonNode): List<OsmPub> = root.path("elements").mapNotNull { e ->
            val tags = e.path("tags")
            fun tag(k: String) = tags.path(k).takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            val name = tag("name") ?: return@mapNotNull null
            if (tag("disused:amenity") != null || tag("opening_hours") == "closed") return@mapNotNull null
            val lat = e.path("lat").takeIf { it.isNumber } ?: e.path("center").path("lat")
            val lng = e.path("lon").takeIf { it.isNumber } ?: e.path("center").path("lon")
            if (!lat.isNumber || !lng.isNumber) return@mapNotNull null
            val street = listOfNotNull(tag("addr:housenumber"), tag("addr:street")).joinToString(" ").ifBlank { null }
            val address = listOfNotNull(street, tag("addr:postcode")).joinToString(", ").ifBlank { null }
            OsmPub(
                ref = "${e.path("type").asText()}/${e.path("id").asLong()}",
                name = name.take(100),
                kind = if (tag("amenity") == "bar") PubKind.BAR else PubKind.PUB,
                lat = lat.asDouble(), lng = lng.asDouble(),
                address = address?.take(200),
                website = (tag("website") ?: tag("contact:website"))?.takeIf { it.startsWith("http") }?.take(300),
                openingHours = tag("opening_hours")?.take(200),
                // 중계는 live_sport=yes (종목별 live_sport:soccer=yes). sport=*는 가게 안 운동(당구 등)이라 보지 않는다
                showsFootball = tag("live_sport:soccer") == "yes" || (tag("live_sport") == "yes" && tag("live_sport:soccer") != "no"),
            )
        }
    }
}

data class ImportResult(val area: String, val fetched: Int, val created: Int, val updated: Int, val removed: Int, val at: OffsetDateTime)
data class ImportStatus(val running: Boolean, val results: Map<String, ImportResult>, val errors: Map<String, String>)

/**
 * OSM 펍을 지역 단위로 DB에 맞춘다 (OSM id로 upsert).
 * - 이름·위치·주소·영업시간은 OSM 값으로 덮는다
 * - 사람이 붙인 정보(팀 팬 펍·중계·메모·숨김)는 그대로 둔다. 중계는 OSM이 "틀어 준다"고 할 때만 켠다
 * - OSM에서 사라진 곳은 지운다 — 단 사람이 정보를 붙였거나 숨긴 곳은 남긴다
 *
 * 처음 뜰 때 OSM 펍이 하나도 없으면 한 번, 그 뒤 매달 다시 받는다.
 */
@Service
class OsmPubImporter(
    private val overpass: OverpassClient,
    private val pubs: PubRepository,
    private val tx: TransactionTemplate,
    @Value("\${travel.osm.import-on-start:true}") private val importOnStart: Boolean,
    @Value("\${travel.osm.scheduled:true}") private val scheduled: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val results = ConcurrentHashMap<String, ImportResult>()
    private val errors = ConcurrentHashMap<String, String>()

    override fun run(args: ApplicationArguments) {
        if (importOnStart && pubs.countBySource(PubSource.OSM) == 0L) startAsync(UkArea.entries)
    }

    @Scheduled(cron = "\${travel.osm.cron:0 30 4 1 * *}", zone = "Asia/Seoul")
    fun monthly() {
        if (scheduled) startAsync(UkArea.entries)
    }

    fun status() = ImportStatus(running.get(), results.toMap(), errors.toMap())

    /** @return 시작했으면 true, 이미 돌고 있으면 false */
    fun startAsync(areas: List<UkArea>): Boolean {
        if (!running.compareAndSet(false, true)) return false
        Thread({
            try {
                areas.forEach { area ->
                    runCatching { importArea(area) }
                        .onSuccess { errors.remove(area.id) }
                        .onFailure {
                            errors[area.id] = it.message ?: it.javaClass.simpleName
                            log.warn("⚠️ [OSM] {} 펍을 못 받았습니다: {}", area.id, it.message)
                        }
                }
            } finally {
                running.set(false)
            }
        }, "osm-import").apply { isDaemon = true }.start()
        return true
    }

    fun importArea(area: UkArea): ImportResult = apply(area, overpass.pubs(area))

    /** 받아 온 목록을 그 지역 DB 행에 맞춘다 (한 트랜잭션) */
    fun apply(area: UkArea, fetched: List<OsmPub>): ImportResult = tx.execute {
        val now = OffsetDateTime.now()
        val existing = pubs.findAllBySourceAndCity(PubSource.OSM, area.id).associateBy { it.osmRef }
        var created = 0
        var updated = 0
        val seen = HashSet<String>()
        val toSave = ArrayList<Pub>()
        for (o in fetched) {
            if (!seen.add(o.ref)) continue
            val p = existing[o.ref]
            if (p == null) {
                created++
                toSave += Pub(
                    name = o.name, club = null, lat = o.lat, lng = o.lng, address = o.address, note = null,
                    kind = o.kind, city = area.id, source = PubSource.OSM, osmRef = o.ref,
                    website = o.website, openingHours = o.openingHours, showsFootball = o.showsFootball, updatedAt = now,
                )
            } else if (p.name != o.name || p.lat != o.lat || p.lng != o.lng || p.address != o.address ||
                p.website != o.website || p.openingHours != o.openingHours || p.kind != o.kind || (o.showsFootball && !p.showsFootball)
            ) {
                updated++
                p.name = o.name; p.lat = o.lat; p.lng = o.lng; p.address = o.address
                p.website = o.website; p.openingHours = o.openingHours; p.kind = o.kind
                if (o.showsFootball) p.showsFootball = true
                p.updatedAt = now
                toSave += p
            }
        }
        pubs.saveAll(toSave)
        val gone = existing.values.filter { it.osmRef !in seen && !it.curated && !it.hidden }
        pubs.deleteAll(gone)
        ImportResult(area.id, fetched.size, created, updated, gone.size, now).also {
            results[area.id] = it
            log.info("✅ [OSM] {} 펍 {}곳 (새로 {}, 바뀜 {}, 없어짐 {})", area.id, it.fetched, created, updated, gone.size)
        }
    }!!
}
