package com.factos.travel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * travel-svc — 축구 여행 (경기장 가이드 · 펍 지도 · 직관 기록 · 킥오프 알림).
 *
 * factos-BE(football-svc)와 **독립된 애플리케이션**이다: 자기 DB(PostGIS)를 쓰고,
 * 축구 데이터는 football-svc의 REST API와 이벤트 스트림(`events:match`)으로만 받는다. DB를 같이 보지 않는다.
 */
@SpringBootApplication
class TravelApplication

fun main(args: Array<String>) {
	runApplication<TravelApplication>(*args)
}
