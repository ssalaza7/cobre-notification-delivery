plugins { `java-library` }

/*
 * Plomeria transversal: implementa los puertos de `domain` con tecnologia concreta.
 *
 * Aqui entra lo que necesita mas de un ejecutable. Nada de esto sabe que es una
 * notificacion: son adaptadores y utilidades. Si algo lo usa un solo ejecutable, vive en
 * ese ejecutable, no aqui.
 */
dependencies {
    api(project(":domain"))

    api("org.springframework.boot:spring-boot-starter-json")

    // Persistencia: los tres ejecutables leen y escriben las mismas tablas.
    api("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    // Flyway corre sobre JDBC una sola vez al arrancar; el runtime sigue siendo R2DBC.
    // En Spring Boot 4 su autoconfiguracion vive en un modulo aparte y necesita un
    // DataSource: sin spring-boot-flyway y el starter JDBC, las migraciones no corren.
    api("org.springframework.boot:spring-boot-flyway")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cola de trabajo: el consumer encola, el worker reencola y la api encola reenvios.
    api(platform("software.amazon.awssdk:bom:${rootProject.extra["awsSdkVersion"]}"))
    api("software.amazon.awssdk:sqs")
    api("software.amazon.awssdk:netty-nio-client")

    // Observabilidad: los tres publican metricas y escriben logs correlacionados.
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-registry-prometheus")
    api("io.micrometer:micrometer-registry-datadog")
    api("io.micrometer:context-propagation")
}
