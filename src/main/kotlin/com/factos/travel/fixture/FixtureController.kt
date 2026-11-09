package com.factos.travel.fixture

import com.factos.travel.club.Club
import com.factos.travel.stadium.StadiumRepository
import com.factos.travel.stadium.StadiumResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class FixtureResponse(
    val matchId: String,
    val club: String,
    val opponent: String,
    val kickoff: OffsetDateTime,
    val isHome: Boolean,
    val tournament: String,
    val stadium: StadiumResponse?, // 홈 경기면 우리 경기장. 원정 경기장은 아직 모른다
)

@RestController
@RequestMapping("/api/travel")
class FixtureController(
    private val fixtures: FixtureRepository,
    private val stadiums: StadiumRepository,
) {
    /** 직관 계획용 다가올 경기 (취소·끝난 경기 제외). 킥오프 3시간 뒤까지는 보여 준다 */
    @GetMapping("/clubs/{club}/fixtures")
    fun upcoming(@PathVariable club: String): List<FixtureResponse> {
        val c = Club.require(club)
        val home = stadiums.findByClub(c.id)?.let(StadiumResponse::of)
        return fixtures.findAllByClubAndStatusAndKickoffAfterOrderByKickoff(c.id, FixtureStatus.SCHEDULED, OffsetDateTime.now().minusHours(3))
            .map { FixtureResponse(it.matchId, it.club, it.opponent, it.kickoff, it.isHome, it.tournament, if (it.isHome) home else null) }
    }
}
