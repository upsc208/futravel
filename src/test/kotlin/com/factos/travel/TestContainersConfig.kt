package com.factos.travel

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 실제 PostGIS·Redis (Docker 필요). H2로는 PostGIS 공간 쿼리를 검증할 수 없다.
 * PostGIS 이미지는 운영(arm64)과 같은 다중 아키텍처 이미지를 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {

    @Bean
    @ServiceConnection
    fun postgis(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("imresamu/postgis:16-3.5").asCompatibleSubstituteFor("postgres"))

    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
}
