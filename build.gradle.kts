plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.7"
}

group = "com.cobre"
version = "0.0.1-SNAPSHOT"
description = "Entrega de notificaciones de eventos via webhook y API self-service (Cobre)"

java {
    // Toolchain y no sourceCompatibility: Gradle localiza (o descarga) un JDK 21
    // aunque el demonio corra sobre otra version, asi la compilacion es reproducible
    // en cualquier maquina y en CI.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springBootVersion = "4.0.7"
val reactorRabbitVersion = "1.5.6"
val amqpClientVersion = "5.14.2"
val awsSdkVersion = "2.31.78"
val reactorKafkaVersion = "1.3.23"
val kafkaClientsVersion = "3.9.1"

configurations.all {
    // Spring Boot 4 sube amqp-client a 5.27.x, incompatible con reactor-rabbitmq 1.5.6
    // (NoSuchMethodError en ConnectionFactory.useNio). Se fija la ultima version que
    // funciona con esa combinacion.
    resolutionStrategy.force("com.rabbitmq:amqp-client:$amqpClientVersion")

    // Mismo caso con Kafka: Boot 4 sube kafka-clients a 4.x, que elimino constructores
    // contra los que reactor-kafka 1.3.23 esta compilado (NoSuchMethodError en
    // ConsumerRecord). Se fija la ultima 3.x, que es con la que esa version funciona.
    resolutionStrategy.force("org.apache.kafka:kafka-clients:$kafkaClientsVersion")
}

dependencies {
    val bom = platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    implementation(bom)
    testImplementation(bom)

    // Adaptador de entrada web (no bloqueante)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Seguridad: JWT HS256 como resource server reactivo
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")

    // Adaptador de salida de persistencia
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    // Flyway corre sobre JDBC una sola vez al arrancar; el runtime sigue siendo R2DBC.
    // En Spring Boot 4 la autoconfiguracion de Flyway vive en su propio modulo y
    // necesita un DataSource: sin spring-boot-flyway y el starter JDBC, flyway-core
    // queda en el classpath pero las migraciones nunca corren.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Mensajeria. El proveedor se elige con cobre.messaging.provider:
    //   rabbit -> un solo broker haciendo de bus de eventos y de cola de trabajo
    //   aws    -> Kafka como bus de eventos y SQS como cola de trabajo
    // Ambos implementan los mismos puertos; el dominio no sabe cual esta activo.
    implementation("io.projectreactor.rabbitmq:reactor-rabbitmq:$reactorRabbitVersion")
    implementation("io.projectreactor.kafka:reactor-kafka:$reactorKafkaVersion")
    implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:netty-nio-client")

    // Observabilidad. Los dos registries conviven: el codigo publica una sola vez a
    // traves de MetricsPort y Micrometer se encarga de alimentar a quien este activo.
    // Datadog viene desactivado y solo se enciende con COBRE_DATADOG_ENABLED=true.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-registry-datadog")
    // Propagacion de ThreadLocal (el MDC) a traves de los saltos de hilo de Reactor.
    implementation("io.micrometer:context-propagation")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
}

/*
 * Se excluyen de la cobertura el arranque, el cableado de beans y los adaptadores de
 * persistencia: su comportamiento no se puede verificar sin una base de datos real,
 * asi que se cubren con la verificacion end-to-end documentada en el README. El
 * siguiente paso natural es Testcontainers, que los traeria de vuelta al gate.
 */
val coverageExclusions = listOf(
    "**/NotificationDeliveryServiceApplication.class",
    "**/infrastructure/config/**",
    "**/infrastructure/adapter/out/persistence/**",
)

fun coveredClasses() = files(
    sourceSets.main.get().output.classesDirs.map { dir ->
        fileTree(dir) { exclude(coverageExclusions) }
    }
)

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(coveredClasses())
    reports {
        html.required = true
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(coveredClasses())
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

// El gate de cobertura corre con `./gradlew build`, no solo cuando se pide aparte.
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
