package com.factos.travel.event

import com.factos.travel.fixture.FixtureService
import com.factos.travel.fixture.ProcessedEvent
import com.factos.travel.fixture.ProcessedEventRepository
import com.factos.travel.member.MemberProfileService
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * football-svc 이벤트 하나를 반영한다 — 종류(`match.*` / `member.*`)에 따라 나눠 보낸다.
 *
 * 처리 기록(processed_event)과 반영이 **한 트랜잭션**이라, 중간에 죽으면 둘 다 없던 일이 되고 다시 받는다.
 * 이벤트 id는 football-svc 아웃박스 id라 스트림이 달라도 겹치지 않는다.
 */
@Service
class EventApplier(
    private val processed: ProcessedEventRepository,
    private val fixtures: FixtureService,
    private val members: MemberProfileService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 반영했으면 true, 이미 처리한 이벤트면 false */
    @Transactional
    fun apply(eventId: Long, type: String, payload: JsonNode): Boolean {
        if (processed.existsById(eventId)) return false
        processed.save(ProcessedEvent(eventId))
        when {
            type.startsWith("match.") -> fixtures.applyMatchEvent(eventId, type, payload)
            type == "member.updated" -> members.upsert(payload)
            type == "member.deleted" -> members.withdraw(payload.path("memberId").asLong())
            else -> log.info("ℹ️ [Event] 모르는 이벤트는 건너뜁니다: {} (id={})", type, eventId)
        }
        return true
    }
}
