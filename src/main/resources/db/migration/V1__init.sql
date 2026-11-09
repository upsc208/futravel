-- travel-svc 첫 스키마. factos-BE와 다른 DB다 (MSA: 서비스마다 자기 DB).
-- 위치는 위경도(lat/lng)만 쓰고, 공간 검색용 geography 열은 거기서 자동으로 만든다.

CREATE EXTENSION IF NOT EXISTS postgis;

-- Big6 홈구장 (경기장 가이드)
CREATE TABLE stadium (
    id        BIGSERIAL PRIMARY KEY,
    club      VARCHAR(20)  NOT NULL UNIQUE,   -- factos 팀 id (arsenal …), factos-BE Club.id와 같은 값
    name      VARCHAR(100) NOT NULL,
    name_ko   VARCHAR(100) NOT NULL,
    city      VARCHAR(50)  NOT NULL,
    lat       DOUBLE PRECISION NOT NULL,
    lng       DOUBLE PRECISION NOT NULL,
    transit   TEXT,                           -- 가까운 역·교통 안내
    location  geography(Point, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED
);

-- 펍 지도 (경기 전후로 팬이 모이는 곳). 관리자가 넣는다
CREATE TABLE pub (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    club       VARCHAR(20),                   -- 특정 팀 팬이 모이는 곳이면 팀 id, 아니면 NULL
    lat        DOUBLE PRECISION NOT NULL,
    lng        DOUBLE PRECISION NOT NULL,
    address    VARCHAR(200),
    note       VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    location   geography(Point, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED
);
CREATE INDEX idx_pub_location ON pub USING GIST (location);

-- 경기 일정 사본. football-svc가 주인이고, 여기는 이벤트·API로 받아 맞춘다 (팀 기준 — 맞대결은 두 행)
CREATE TABLE fixture (
    id         BIGSERIAL PRIMARY KEY,
    match_id   VARCHAR(32)  NOT NULL,         -- FotMob matchId
    club       VARCHAR(20)  NOT NULL,
    opponent   VARCHAR(100) NOT NULL,
    kickoff    TIMESTAMPTZ  NOT NULL,
    is_home    BOOLEAN      NOT NULL,
    tournament VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL,         -- SCHEDULED | CANCELLED | FINISHED
    home_score INT,
    away_score INT,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_fixture_match_club UNIQUE (match_id, club)
);
CREATE INDEX idx_fixture_club_kickoff ON fixture (club, kickoff);

-- 처리한 이벤트 id (football-svc 아웃박스 id). 같은 이벤트가 두 번 와도 한 번만 반영한다
CREATE TABLE processed_event (
    event_id     BIGINT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
