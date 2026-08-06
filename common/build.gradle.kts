// java-library y no java: `api` propaga las dependencias a los tres ejecutables, que es
// lo que se quiere aqui —quien depende de common necesita R2DBC y SQS en su classpath—,
// mientras que `implementation` las ocultaria.
plugins { `java-library` }

/*
 * Libreria compartida. No arranca: no lleva el plugin de Spring Boot.
 *
 * Contiene lo que necesitan dos o mas ejecutables. Esa es la regla de entrada: mientras
 * algo lo use un solo modulo, vive en ese modulo. Cada dependencia que entra aqui la
 * cargan los tres jars aunque dos no la usen.
 */
dependencies {
    // Serializacion de los mensajes de cola: la usan los tres.
    api("org.springframework.boot:spring-boot-starter-json")

    // Persistencia: los tres modulos leen y escriben las mismas tablas.
    api("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    // Flyway corre sobre JDBC una sola vez al arrancar; el runtime sigue siendo R2DBC.
    // En Spring Boot 4 su autoconfiguracion vive en un modulo aparte y necesita un
    // DataSource: sin spring-boot-flyway y el starter JDBC, flyway-core queda en el
    // classpath pero las migraciones nunca corren.
    api("org.springframework.boot:spring-boot-flyway")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cola de trabajo: el consumidor encola lo ingestado, el worker reencola los
    // reintentos y la API encola los reenvios manuales.
    api(platform("software.amazon.awssdk:bom:${rootProject.extra["awsSdkVersion"]}"))
    api("software.amazon.awssdk:sqs")
    api("software.amazon.awssdk:netty-nio-client")

    // Observabilidad: los tres publican metricas y escriben logs correlacionados.
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-registry-prometheus")
    api("io.micrometer:micrometer-registry-datadog")
    // Propagacion del MDC a traves de los saltos de hilo de Reactor.
    api("io.micrometer:context-propagation")
}
