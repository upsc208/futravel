package com.factos.travel.common

import com.factos.travel.club.NotFound
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest

/**
 * - 관리자 API(`/api/travel/admin` 아래): X-Admin-Key 헤더. 키가 비어 있으면 전부 잠긴다
 * - 로그인 쿠키로 쓰는 요청(POST·PUT·DELETE): Origin이 우리 사이트여야 한다 (CSRF — football-svc OriginCheckInterceptor와 같은 규칙)
 * - CORS: 로컬 개발에서 프론트(3000)가 이 서비스(8090)를 직접 부를 때만 필요하다. 운영은 nginx 뒤 같은 출처
 */
@Configuration
class WebConfig(
    @Value("\${travel.admin.key:}") private val adminKey: String,
    @Value("\${travel.web.allowed-origins:http://localhost:3000}") allowedOrigins: String,
) : WebMvcConfigurer {

    private val origins = allowedOrigins.split(",").map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                val given = request.getHeader("X-Admin-Key").orEmpty()
                val ok = adminKey.isNotBlank() && MessageDigest.isEqual(given.toByteArray(), adminKey.toByteArray())
                if (!ok) response.sendError(HttpStatus.FORBIDDEN.value(), "관리자만 할 수 있습니다.")
                return ok
            }
        }).addPathPatterns("/api/travel/admin/**")

        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (request.method in SAFE_METHODS) return true
                val origin = request.getHeader("Origin")?.trimEnd('/')
                if (origin != null && origin in origins) return true
                response.status = HttpStatus.FORBIDDEN.value()
                response.contentType = "application/json;charset=UTF-8"
                response.writer.write("""{"error":"허용되지 않은 출처의 요청입니다."}""")
                return false
            }
        }).addPathPatterns("/api/travel/**").excludePathPatterns("/api/travel/admin/**")
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/travel/**")
            .allowedOrigins(*origins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowCredentials(true)
    }

    companion object {
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}

/** 오류 응답은 football-svc와 같은 모양 `{"error": "..."}` — 프론트가 그대로 띄운다 */
@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(NotFound::class)
    fun notFound(e: NotFound) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException) = ResponseEntity.status(e.statusCode).body(mapOf("error" to (e.reason ?: "요청을 처리하지 못했습니다.")))

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) =
        ResponseEntity.badRequest().body(mapOf("error" to (e.bindingResult.fieldErrors.firstOrNull()?.let { "${it.field}: ${it.defaultMessage}" } ?: "잘못된 요청입니다.")))
}
