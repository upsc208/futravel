package com.factos.travel.event

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * football-svc 이벤트(`events:match` 경기, `events:member` 회원)를 소비자 그룹으로 읽어 사본에 반영한다.
 * 계약: factos-BE docs/events.md
 *
 * - 소비자 그룹이라 여러 인스턴스를 띄워도 이벤트 하나는 한 곳에서만 처리된다
 * - 반영한 뒤에 XACK. 반영 중 죽으면 ack가 안 돼 대기열(PEL)에 남고, 다음에 뜰 때 [drainPending]이 다시 처리한다
 * - "최소 한 번" 전달이라 같은 이벤트가 또 올 수 있다 → [EventApplier]가 이벤트 id로 거른다
 * - Redis가 없으면 그룹이 생길 때까지 [RETRY]마다 다시 시도한다 (앱은 그대로 뜬다)
 */
@Component
class EventConsumer(
    private val redis: StringRedisTemplate,
    private val connectionFactory: RedisConnectionFactory,
    private val applier: EventApplier,
    private val objectMapper: ObjectMapper,
    @Value("\${travel.events.enabled:true}") private val enabled: Boolean,
    @Value("\${travel.events.group:travel-svc}") private val group: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    // 인스턴스가 하나라 이름을 고정한다 — 재시작해도 자기 대기열(PEL)을 이어받는다
    private val consumer = Consumer.from(group, "travel-1")
    @Volatile private var container: StreamMessageListenerContainer<String, MapRecord<String, String, String>>? = null
    @Volatile private var stopped = false

    override fun run(args: ApplicationArguments) {
        if (!enabled) return
        Thread({ startWhenRedisReady() }, "match-event-consumer").apply { isDaemon = true }.start()
    }

    private fun startWhenRedisReady() {
        while (!stopped) {
            try {
                ensureGroup()
                drainPending()
                start()
                log.info("✅ [Event] {} 소비 시작 (그룹 {})", STREAMS, group)
                return
            } catch (e: Exception) {
                log.warn("⚠️ [Event] 소비 시작 실패, {}초 뒤 다시: {}", RETRY.seconds, e.message)
                Thread.sleep(RETRY.toMillis())
            }
        }
    }

    /** 그룹이 없으면 만든다. 처음 만들 때는 스트림 처음(0)부터 — 남아 있는 이벤트를 다 받는다 */
    fun ensureGroup() {
        for (stream in STREAMS) {
            try {
                connectionFactory.connection.use {
                    it.streamCommands().xGroupCreate(stream.toByteArray(), group, ReadOffset.from("0"), true)
                }
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.none { it.message?.contains("BUSYGROUP") == true }) throw e
            }
        }
    }

    /**
     * 지난번에 받았지만 ack하지 못한 이벤트를 먼저 한 번 처리한다.
     * 한 번만 돈다 — 계속 실패하는 이벤트가 있어도 여기서 멈추지 않게 (다음 재시작 때 또 시도)
     */
    fun drainPending() {
        for (stream in STREAMS) {
            redis.opsForStream<String, String>()
                .read(consumer, StreamOffset.create(stream, ReadOffset.from("0")))
                .orEmpty()
                .forEach(::handle)
        }
    }

    private fun start() {
        val options = StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofSeconds(2))
            .errorHandler { log.warn("⚠️ [Event] 읽기 오류: {}", it.message) }
            .build()
        val c = StreamMessageListenerContainer.create(connectionFactory, options)
        STREAMS.forEach { c.receive(consumer, StreamOffset.create(it, ReadOffset.lastConsumed()), ::handle) }
        c.start()
        container = c
    }

    /** 이벤트 하나 처리 → ack. 처리에 실패하면 ack하지 않는다 (다음 재시작 때 다시) */
    fun handle(record: MapRecord<String, String, String>) {
        val v = record.value
        try {
            val id = v["id"]?.toLongOrNull() ?: throw IllegalArgumentException("id가 없습니다")
            val applied = applier.apply(id, v["type"].orEmpty(), objectMapper.readTree(v["payload"] ?: "{}"))
            if (applied) log.info("📥 [Event] {} {} (id={})", v["type"], v["aggregateId"], id)
        } catch (e: IllegalArgumentException) {
            // 모양이 잘못된 이벤트는 다시 받아도 똑같이 실패한다 — 기록만 하고 넘긴다
            log.error("❌ [Event] 처리할 수 없는 이벤트 {}: {}", record.id, e.message)
        } catch (e: Exception) {
            log.error("❌ [Event] 이벤트 {} 처리 실패 (다음에 다시): {}", record.id, e.message, e)
            return
        }
        redis.opsForStream<String, String>().acknowledge(group, record)
    }

    @PreDestroy
    fun stop() {
        stopped = true
        container?.stop()
    }

    companion object {
        const val MATCH_STREAM = "events:match"
        const val MEMBER_STREAM = "events:member"
        val STREAMS = listOf(MATCH_STREAM, MEMBER_STREAM)
        private val RETRY: Duration = Duration.ofSeconds(10)
    }
}
