plugins { `java-library` }

/*
 * Libreria compartida por los tres servicios. Contiene dos capas con reglas distintas:
 *
 *   domain             modelo, reglas y puertos. No puede importar ningun framework.
 *   infrastructure     adaptadores: R2DBC, SQS, metricas, logs.
 *
 * Al vivir el dominio en el mismo modulo que los adaptadores, Spring y el SDK de AWS
 * estan en su classpath y el compilador ya no impide un import prohibido. Lo verifica
 * DominioSinFrameworkTest, que falla el build si aparece uno.
 *
 * Entra aqui lo que necesitan al menos dos servicios. Si algo lo usa uno solo, vive en
 * ese servicio. Y si manana un servicio necesita algo que el kit no soporta -otra
 * implementacion de persistencia, por ejemplo- la trae el, sin cambiar el kit.
 */
dependencies {
    api("io.projectreactor:reactor-core")

    api("org.springframework.boot:spring-boot-starter-json")

    // Persistencia relacional: suscripciones y credenciales. Es configuracion del
    // cliente, no parte del flujo de entrega, y se queda en Postgres.
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

    // Notificaciones e intentos de entrega. El cliente asincrono devuelve
    // CompletableFuture, que se envuelve en Mono sin bloquear ningun hilo.
    api("software.amazon.awssdk:dynamodb")

    // Observabilidad: los tres publican metricas y escriben logs correlacionados.
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-registry-prometheus")
    api("io.micrometer:micrometer-registry-datadog")
    api("io.micrometer:context-propagation")
}
