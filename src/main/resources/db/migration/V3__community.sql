-- 커뮤니티 (직관 후기·여행 팁·질문). 회원은 football-svc에만 있고, 여기는 작성자 표시용 사본만 둔다.

-- 회원 사본. football-svc의 member.updated / member.deleted 이벤트로 맞춘다 (처음 보는 회원은 /api/auth/me로 한 번 채운다)
CREATE TABLE member_profile (
    member_id         BIGINT PRIMARY KEY,           -- football-svc 회원 id (로그인 JWT의 sub)
    nickname          VARCHAR(40)  NOT NULL,
    profile_image_url VARCHAR(500),
    favorite_team     VARCHAR(20),
    signup_completed  BOOLEAN      NOT NULL,
    role              VARCHAR(20)  NOT NULL,        -- MEMBER | TEAM_ADMIN | ADMIN
    admin_club        VARCHAR(20),
    suspended_until   TIMESTAMPTZ,
    suspension_reason VARCHAR(500),
    withdrawn         BOOLEAN      NOT NULL DEFAULT FALSE,  -- 탈퇴. 글은 남기고 "탈퇴한 회원"으로 보여 준다
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE post (
    id            BIGSERIAL PRIMARY KEY,
    author_id     BIGINT       NOT NULL,            -- member_profile.member_id (FK는 걸지 않는다 — 사본이 늦게 올 수 있다)
    club          VARCHAR(20),                       -- 어느 팀 이야기인지 (NULL = 팀 무관)
    category      VARCHAR(20)  NOT NULL,             -- REVIEW | TIP | QUESTION | FREE
    title         VARCHAR(100) NOT NULL,
    content       TEXT         NOT NULL,
    comment_count INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_post_created ON post (created_at DESC);
CREATE INDEX idx_post_club_created ON post (club, created_at DESC);

CREATE TABLE post_comment (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT       NOT NULL REFERENCES post (id) ON DELETE CASCADE,
    author_id  BIGINT       NOT NULL,
    content    VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_comment_post_created ON post_comment (post_id, created_at);
