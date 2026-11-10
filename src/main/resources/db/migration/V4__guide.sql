-- 경기장 가이드 2: 펍 데이터 출처(OpenStreetMap) · 경기장 안 굿즈·음식 가격 · 회원 제보

-- ── 펍 ───────────────────────────────────────────────────────
-- 기본 목록은 OpenStreetMap(Overpass)에서 도시 단위로 받아 온다 (OsmPubImporter).
-- "이 팀 팬이 모이는 곳(club)"·"축구 중계(shows_football)"·한국어 메모(note)는 사람이 붙인다 (관리자 / 회원 제보 승인)
ALTER TABLE pub
    ADD COLUMN kind           VARCHAR(10)  NOT NULL DEFAULT 'PUB',    -- PUB | BAR
    ADD COLUMN city           VARCHAR(50),                          -- 가져온 지역 (London …)
    ADD COLUMN source         VARCHAR(10)  NOT NULL DEFAULT 'ADMIN',  -- ADMIN | OSM
    ADD COLUMN osm_ref        VARCHAR(30),                          -- node/123 · way/456 (OSM에서 온 행)
    ADD COLUMN website        VARCHAR(300),
    ADD COLUMN opening_hours  VARCHAR(200),                         -- OSM opening_hours 원문
    ADD COLUMN shows_football BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN hidden         BOOLEAN      NOT NULL DEFAULT FALSE,  -- 관리자가 지운 OSM 펍 (다시 가져와도 안 보이게)
    ADD COLUMN updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now();
CREATE UNIQUE INDEX uk_pub_osm_ref ON pub (osm_ref) WHERE osm_ref IS NOT NULL;
CREATE INDEX idx_pub_club ON pub (club) WHERE club IS NOT NULL;
CREATE INDEX idx_pub_city ON pub (city);

-- ── 경기장 안 굿즈·음식 ──────────────────────────────────────
-- 어디에도 API가 없어서 사람이 정리한다. 가격은 펜스(1/100 파운드), 언제 확인한 값인지(checked_on)를 같이 둔다
CREATE TABLE stadium_item (
    id          BIGSERIAL PRIMARY KEY,
    club        VARCHAR(20)  NOT NULL,
    category    VARCHAR(10)  NOT NULL,      -- FOOD | DRINK | GOODS | TOUR
    name        VARCHAR(100) NOT NULL,      -- 현지 이름 (Chicken Balti Pie)
    name_ko     VARCHAR(100),               -- 한국어 이름 (치킨 발티 파이)
    price_pence INT,                        -- NULL이면 가격 모름
    note        VARCHAR(300),               -- 한국어 팁 (카드만 받음, 하프타임엔 줄이 길다 …)
    checked_on  DATE,                       -- 이 가격을 본 날
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_item_price CHECK (price_pence IS NULL OR price_pence BETWEEN 0 AND 100000)
);
CREATE INDEX idx_stadium_item_club ON stadium_item (club, category);

-- 회원 제보: 가격("파이 £4.80이었어요") 또는 펍 정보("이 펍 아스날 팬 펍이에요"). 관리자가 승인하면 반영된다
CREATE TABLE guide_report (
    id             BIGSERIAL PRIMARY KEY,
    kind           VARCHAR(10)  NOT NULL,   -- ITEM | PUB
    club           VARCHAR(20),             -- ITEM: 그 경기장 · PUB: 제보한 "팬 펍" 팀 (없으면 NULL)
    item_id        BIGINT REFERENCES stadium_item (id) ON DELETE CASCADE,  -- ITEM: 기존 항목 가격 수정이면
    pub_id         BIGINT REFERENCES pub (id) ON DELETE CASCADE,           -- PUB: 대상 펍
    category       VARCHAR(10),
    name           VARCHAR(100),
    price_pence    INT,
    shows_football BOOLEAN,
    note           VARCHAR(300),
    seen_on        DATE,
    reporter_id    BIGINT       NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED
    reviewer_id    BIGINT,
    reviewed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_guide_report_status ON guide_report (status, created_at);
CREATE INDEX idx_guide_report_reporter ON guide_report (reporter_id, created_at);
