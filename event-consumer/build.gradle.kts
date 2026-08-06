plugins { id("org.springframework.boot") }

/*
 * Consumidor del bus. Su unica dependencia propia es el cliente de Kafka: no conoce el
 * cliente de webhooks ni la cadena de seguridad de la API.
 */
dependencies {
    implementation(project(":common"))
    implementation("io.projectreactor.kafka:reactor-kafka:1.3.23")

    // No sirve trafico de negocio: expone /actuator para las sondas del orquestador y
    // para que Prometheus pueda raspar las metricas. Sin servidor, un consumidor caido
    // no se distingue de uno sano.
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}

configurations.all {
    // Boot 4 sube kafka-clients a 4.x y elimina constructores contra los que
    // reactor-kafka 1.3.23 esta compilado (NoSuchMethodError en ConsumerRecord). Se fija
    // la ultima 3.x, que es con la que esa version funciona.
    //
    // Queda encerrado en este modulo: antes de separar, este `force` afectaba al build
    // completo, incluidos dos ejecutables que no usan Kafka.
    resolutionStrategy.force("org.apache.kafka:kafka-clients:${rootProject.extra["kafkaClientsVersion"]}")
}
