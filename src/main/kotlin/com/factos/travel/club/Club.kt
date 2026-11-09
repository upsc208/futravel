package com.factos.travel.club

/**
 * factos가 다루는 EPL Big6. [id]는 factos-BE `Club.id`·프론트 라우트와 같은 값이다.
 *
 * football-svc의 enum을 가져다 쓰지 않고 따로 둔다 — 서비스끼리 코드를 공유하면 같이 배포해야 한다.
 * 서로 약속한 건 **id 문자열**뿐이다.
 */
enum class Club(val id: String, val nameKo: String) {
    ARSENAL("arsenal", "아스날"),
    CHELSEA("chelsea", "첼시"),
    LIVERPOOL("liverpool", "리버풀"),
    MAN_CITY("mancity", "맨시티"),
    MAN_UTD("manutd", "맨유"),
    TOTTENHAM("tottenham", "토트넘");

    companion object {
        fun fromId(id: String): Club? = entries.find { it.id == id }
        fun require(id: String): Club = fromId(id) ?: throw NotFound("알 수 없는 팀입니다: $id")
    }
}

class NotFound(message: String) : RuntimeException(message)
