# factos-TRAVEL (travel-svc)

factos(EPL Big6)의 **축구 여행** 서비스. 경기장 가이드 · 펍 지도 · 직관 기록 · 킥오프 알림.
football-svc(`../factos-BE`)와 **독립된 애플리케이션**이다 (MSA).

- Kotlin 2.1 / Spring Boot 3.5 / JDK 21, Gradle Kotlin DSL. 패키지 `com.factos.travel`
- DB: **자기 PostgreSQL + PostGIS** (factos-BE DB와 다르다). 스키마는 Flyway(`db/migration`)만 바꾼다 — `ddl-auto=validate`
- 포트 8090 (로컬에서 factos-BE 8080과 같이 띄우려고)

## football-svc와 통신 — DB를 같이 보지 않는다

- **REST**: `FootballClient` → `GET /api/matches/clubs/{club}/upcoming`. 뜰 때 한 번 일정을 받아 둔다 (`FixtureBackfill`)
- **이벤트**: Redis Stream `events:match`·`events:member`를 소비자 그룹 `travel-svc`로 읽는다 (`EventConsumer` → `EventApplier`). 계약은 `../factos-BE/docs/events.md`
  - 최소 한 번 전달 → `processed_event`(이벤트 id)로 중복 제거. 반영과 기록이 한 트랜잭션 (`EventApplier.apply`)
  - 반영 뒤 XACK. 실패하면 ack 안 함 → 다음 기동 때 `drainPending`이 다시 처리
- 팀 id(`arsenal` …)는 factos-BE `Club.id`와 같은 문자열. enum은 공유하지 않고 따로 둔다 (같이 배포하지 않으려고)

## 로그인·회원 — football-svc와 공유

- 회원가입·로그인은 football-svc에서만. 그 쿠키(`factos_session`, JWT)가 같은 도메인이라 여기에도 실린다
- `TravelAuth`: JWT를 football-svc JWKS로 직접 검증 → 회원 id → `member_profile` 사본. 사본이 없거나 예전 쿠키면 `/api/auth/me`로 한 번 채운다
- 사본은 `events:member`(member.updated / deleted)로 맞춘다 — 닉네임 변경·정지·탈퇴가 반영된다
- 쓰기 요청은 Origin 검사 (`travel.web.allowed-origins` = 사이트 주소). 정지 중·가입 전이면 403

## API (`/api/travel`, nginx가 이 접두사만 이 서비스로 보낸다)

- 커뮤니티: `GET /posts?club=&category=`, `GET|PUT|DELETE /posts/{id}`, `POST /posts`, `GET|POST /posts/{id}/comments`, `DELETE /comments/{id}`
  - 말머리 REVIEW(직관 후기) · TIP(여행 팁) · QUESTION(질문) · FREE(자유). 수정은 본인, 삭제는 본인·관리자(전체 또는 그 팀)

- `GET /stadiums`, `GET /clubs/{club}/stadium?radius=1500` (경기장 + 반경 안 펍, PostGIS `ST_DWithin`)
- `GET /clubs/{club}/fixtures` (다가올 경기, 홈이면 경기장 포함)
- 관리자 `POST /admin/pubs`, `DELETE /admin/pubs/{id}` — `X-Admin-Key` (`TRAVEL_ADMIN_KEY`, 비면 잠김)
- 헬스: `/actuator/health` (nginx는 밖에 내지 않는다)

## 명령

```
./gradlew test        # Docker 필요 — Testcontainers가 PostGIS·Redis를 띄운다
./gradlew bootRun     # 로컬 DB: docker run -p 5433:5432 -e POSTGRES_USER=travel -e POSTGRES_PASSWORD=travel -e POSTGRES_DB=travel imresamu/postgis:16-3.5
```

## 규칙

- 위치는 lat/lng만 엔티티에 둔다. `location` geography 열은 DB가 만든다 (GENERATED). 공간 쿼리는 네이티브로
- PostGIS 이미지는 `imresamu/postgis` (arm64 지원). 공식 `postgis/postgis`는 amd64뿐이라 운영(Oracle A1)에서 못 쓴다
- 테스트는 실제 외부 서비스를 부르지 않는다: football-svc는 가짜 서버, 백필은 `test` 프로필에서 끔
- 배포: CI가 arm64 이미지 → Trivy → GHCR → 서버 `deploy.sh travel <sha>` (compose·nginx 설정은 factos-BE `infra/compose`가 주인)
- Spring 6.2 CVE 2건은 `.trivyignore` (2027-03-01 만료) — factos-BE와 같이 Boot 4로
- 프론트 화면: factos-FE `/{club}/travel` (목록·글쓰기·상세+댓글·수정)
- 아직 없는 것: 직관 기록 전용 기능, 킥오프 알림 발송(알림 서비스), 원정 경기장, 금칙어 필터·신고(football-svc 댓글에는 있음)
