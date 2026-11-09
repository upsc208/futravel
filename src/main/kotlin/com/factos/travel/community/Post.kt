package com.factos.travel.community

import jakarta.persistence.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

enum class PostCategory(val label: String) {
    REVIEW("직관 후기"),
    TIP("여행 팁"),
    QUESTION("질문"),
    FREE("자유"),
}

@Entity
@Table(name = "post")
class Post(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "author_id") val authorId: Long,
    var club: String?,
    @Enumerated(EnumType.STRING) var category: PostCategory,
    var title: String,
    var content: String,
    @Column(name = "comment_count") var commentCount: Int = 0,
    @Column(name = "created_at") val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "post_comment")
class PostComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "post_id") val postId: Long,
    @Column(name = "author_id") val authorId: Long,
    val content: String,
    @Column(name = "created_at") val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface PostRepository : JpaRepository<Post, Long> {
    @Query(
        """
        SELECT p FROM Post p
        WHERE (:club IS NULL OR p.club = :club)
          AND (:category IS NULL OR p.category = :category)
        """
    )
    fun search(@Param("club") club: String?, @Param("category") category: PostCategory?, pageable: Pageable): Page<Post>

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    fun addCommentCount(@Param("id") id: Long, @Param("delta") delta: Int): Int
}

interface PostCommentRepository : JpaRepository<PostComment, Long> {
    fun findAllByPostId(postId: Long, pageable: Pageable): Page<PostComment>
    fun findFirstByAuthorIdOrderByCreatedAtDesc(authorId: Long): PostComment?
}
