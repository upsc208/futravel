package com.factos.travel.community

import com.factos.travel.club.Club
import com.factos.travel.club.NotFound
import com.factos.travel.member.MemberProfile
import com.factos.travel.member.MemberProfileRepository
import com.factos.travel.member.TravelAuth
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime

data class Author(val memberId: Long, val nickname: String, val profileImageUrl: String?, val favoriteTeam: String?)

data class PostSummary(
    val id: Long, val club: String?, val category: PostCategory, val categoryLabel: String,
    val title: String, val author: Author, val commentCount: Int, val createdAt: OffsetDateTime,
)

data class PostDetail(
    val id: Long, val club: String?, val category: PostCategory, val categoryLabel: String,
    val title: String, val content: String, val author: Author, val commentCount: Int,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
    val mine: Boolean,       // 보는 사람이 쓴 글 → 수정·삭제 버튼
    val deletable: Boolean,  // 본인 또는 관리자
)

data class CommentView(
    val id: Long, val content: String, val author: Author, val createdAt: OffsetDateTime,
    val mine: Boolean, val deletable: Boolean,
)

data class PostRequest(val club: String? = null, val category: String?, val title: String?, val content: String?)
data class CommentRequest(val content: String?)

/** 직관 후기·여행 팁·질문 게시판. 읽기는 누구나, 쓰기는 football-svc에서 로그인·가입을 마친 회원 */
@RestController
@RequestMapping("/api/travel")
class CommunityController(
    private val posts: PostRepository,
    private val comments: PostCommentRepository,
    private val profiles: MemberProfileRepository,
    private val auth: TravelAuth,
) {
    @GetMapping("/posts")
    fun list(
        @RequestParam(required = false) club: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<PostSummary> {
        val clubId = club?.takeIf { it.isNotBlank() }?.let { Club.require(it).id }
        val cat = category?.takeIf { it.isNotBlank() }?.let(::parseCategory)
        val result = posts.search(clubId, cat, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 50), Sort.by("createdAt").descending()))
        val authors = authorsOf(result.content.map { it.authorId })
        return result.map { p ->
            PostSummary(p.id!!, p.club, p.category, p.category.label, p.title, authors.of(p.authorId), p.commentCount, p.createdAt)
        }
    }

    @GetMapping("/posts/{id}")
    fun detail(@PathVariable id: Long, request: HttpServletRequest): PostDetail {
        val p = posts.findById(id).orElseThrow { NotFound("글을 찾을 수 없습니다.") }
        val viewer = auth.current(request)
        return toDetail(p, viewer)
    }

    @PostMapping("/posts")
    fun create(@RequestBody body: PostRequest, request: HttpServletRequest): ResponseEntity<PostDetail> {
        val me = auth.requireWriter(request)
        val (club, category, title, content) = validate(body)
        val saved = posts.save(Post(authorId = me.memberId, club = club, category = category, title = title, content = content))
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(saved, me))
    }

    @PutMapping("/posts/{id}")
    fun update(@PathVariable id: Long, @RequestBody body: PostRequest, request: HttpServletRequest): PostDetail {
        val me = auth.requireWriter(request)
        val p = posts.findById(id).orElseThrow { NotFound("글을 찾을 수 없습니다.") }
        if (p.authorId != me.memberId) throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 쓴 글만 고칠 수 있습니다.")
        val (club, category, title, content) = validate(body)
        p.club = club
        p.category = category
        p.title = title
        p.content = content
        p.updatedAt = OffsetDateTime.now()
        return toDetail(posts.save(p), me)
    }

    @DeleteMapping("/posts/{id}")
    fun delete(@PathVariable id: Long, request: HttpServletRequest): ResponseEntity<Void> {
        val me = auth.require(request)
        val p = posts.findById(id).orElseThrow { NotFound("글을 찾을 수 없습니다.") }
        if (p.authorId != me.memberId && !me.canModerate(p.club)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 글만 지울 수 있습니다.")
        posts.delete(p) // 댓글은 DB에서 함께 지워진다 (ON DELETE CASCADE)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/posts/{id}/comments")
    fun listComments(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        request: HttpServletRequest,
    ): Page<CommentView> {
        val post = posts.findById(id).orElseThrow { NotFound("글을 찾을 수 없습니다.") }
        val viewer = auth.current(request)
        val result = comments.findAllByPostId(id, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by("createdAt").ascending()))
        val authors = authorsOf(result.content.map { it.authorId })
        return result.map { toView(it, authors.of(it.authorId), viewer, post.club) }
    }

    @PostMapping("/posts/{id}/comments")
    @Transactional
    fun addComment(@PathVariable id: Long, @RequestBody body: CommentRequest, request: HttpServletRequest): ResponseEntity<CommentView> {
        val me = auth.requireWriter(request)
        val post = posts.findById(id).orElseThrow { NotFound("글을 찾을 수 없습니다.") }
        val content = body.content?.trim().orEmpty()
        if (content.isEmpty()) throw IllegalArgumentException("댓글 내용을 입력해 주세요.")
        if (content.length > MAX_COMMENT) throw IllegalArgumentException("댓글은 ${MAX_COMMENT}자까지 쓸 수 있습니다.")
        // 연속 도배 방지 (football-svc 댓글과 같은 간격)
        comments.findFirstByAuthorIdOrderByCreatedAtDesc(me.memberId)?.let {
            if (Duration.between(it.createdAt, OffsetDateTime.now()) < COMMENT_COOLDOWN) {
                throw IllegalArgumentException("댓글은 ${COMMENT_COOLDOWN.seconds}초에 한 번 쓸 수 있습니다.")
            }
        }
        val saved = comments.save(PostComment(postId = id, authorId = me.memberId, content = content))
        posts.addCommentCount(id, 1)
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved, author(me), me, post.club))
    }

    @DeleteMapping("/comments/{commentId}")
    @Transactional
    fun deleteComment(@PathVariable commentId: Long, request: HttpServletRequest): ResponseEntity<Void> {
        val me = auth.require(request)
        val c = comments.findById(commentId).orElseThrow { NotFound("댓글을 찾을 수 없습니다.") }
        val club = posts.findById(c.postId).map { it.club }.orElse(null)
        if (c.authorId != me.memberId && !me.canModerate(club)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 댓글만 지울 수 있습니다.")
        comments.delete(c)
        posts.addCommentCount(c.postId, -1)
        return ResponseEntity.noContent().build()
    }

    // ── 내부 ─────────────────────────────────────────────

    private data class Valid(val club: String?, val category: PostCategory, val title: String, val content: String)

    private fun validate(body: PostRequest): Valid {
        val club = body.club?.takeIf { it.isNotBlank() }?.let { Club.require(it).id }
        val category = parseCategory(body.category ?: throw IllegalArgumentException("말머리를 골라 주세요."))
        val title = body.title?.trim().orEmpty()
        val content = body.content?.trim().orEmpty()
        if (title.isEmpty()) throw IllegalArgumentException("제목을 입력해 주세요.")
        if (title.length > MAX_TITLE) throw IllegalArgumentException("제목은 ${MAX_TITLE}자까지 쓸 수 있습니다.")
        if (content.isEmpty()) throw IllegalArgumentException("내용을 입력해 주세요.")
        if (content.length > MAX_CONTENT) throw IllegalArgumentException("내용은 ${MAX_CONTENT}자까지 쓸 수 있습니다.")
        return Valid(club, category, title, content)
    }

    private fun parseCategory(raw: String) =
        PostCategory.entries.find { it.name == raw.trim().uppercase() } ?: throw IllegalArgumentException("말머리는 ${PostCategory.entries.joinToString { it.name }} 중 하나입니다.")

    private fun author(p: MemberProfile?) =
        if (p == null) Author(0, MemberProfile.WITHDRAWN, null, null)
        else Author(p.memberId, p.displayName, if (p.withdrawn) null else p.profileImageUrl, if (p.withdrawn) null else p.favoriteTeam)

    private class Authors(private val byId: Map<Long, MemberProfile>, private val make: (MemberProfile?) -> Author) {
        fun of(id: Long) = make(byId[id]).let { if (it.memberId == 0L) it.copy(memberId = id) else it }
    }

    /** 작성자를 한 번에 읽는다 (글마다 조회하지 않게) */
    private fun authorsOf(ids: Collection<Long>) = Authors(profiles.findAllById(ids.toSet()).associateBy { it.memberId }, ::author)

    private fun toDetail(p: Post, viewer: MemberProfile?): PostDetail {
        val mine = viewer != null && viewer.memberId == p.authorId
        return PostDetail(
            p.id!!, p.club, p.category, p.category.label, p.title, p.content,
            author(profiles.findById(p.authorId).orElse(null)).let { if (it.memberId == 0L) it.copy(memberId = p.authorId) else it },
            p.commentCount, p.createdAt, p.updatedAt,
            mine = mine, deletable = mine || viewer?.canModerate(p.club) == true,
        )
    }

    private fun toView(c: PostComment, author: Author, viewer: MemberProfile?, club: String?): CommentView {
        val mine = viewer != null && viewer.memberId == c.authorId
        return CommentView(c.id!!, c.content, author, c.createdAt, mine, mine || viewer?.canModerate(club) == true)
    }

    companion object {
        const val MAX_TITLE = 100
        const val MAX_CONTENT = 5000
        const val MAX_COMMENT = 500
        val COMMENT_COOLDOWN: Duration = Duration.ofSeconds(10)
    }
}
