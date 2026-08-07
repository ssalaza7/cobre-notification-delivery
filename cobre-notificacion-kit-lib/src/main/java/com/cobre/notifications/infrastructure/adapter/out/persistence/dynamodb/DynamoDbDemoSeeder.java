package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.cobre.notifications.application.port.out.DeliveryAttemptRepositoryPort;
import com.cobre.notifications.application.port.out.NotificationEventRepositoryPort;
import com.cobre.notifications.application.port.out.SubscriptionRepositoryPort;
import com.cobre.notifications.domain.model.AttemptOutcome;
import com.cobre.notifications.domain.model.DeliveryAttempt;
import com.cobre.notifications.domain.model.DeliveryStatus;
import com.cobre.notifications.domain.model.NotificationEvent;
import com.cobre.notifications.domain.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * Siembra las notificaciones de ejemplo entregadas con la prueba.
 *
 * <p>Ocupa el lugar que tenia la migracion de Flyway para estas filas: las suscripciones
 * y las credenciales siguen sembrandose por SQL, porque siguen en Postgres, y solo los
 * eventos y su bitacora se escriben aqui.
 *
 * <p>Solo en los perfiles de demostracion. Se apoya en la escritura condicional del
 * repositorio, de modo que un segundo arranque no duplica nada ni descuadra el conteo
 * por estado.
 */
@Component
@Profile({"local", "demo"})
public class DynamoDbDemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbDemoSeeder.class);

    private static final String RECURSO = "db/demo/notification-events.json";

    private static final List<String> CLIENTES_DEMO = List.of("CLIENT001", "CLIENT002", "CLIENT003");

    /**
     * Supuesto heredado de la siembra original: el archivo entregado no trae fecha de
     * creacion del evento, solo la de entrega. Como la API filtra por fecha de creacion,
     * se toma dos segundos antes de la entrega, que es el orden de magnitud entre que la
     * plataforma genera un evento y se entrega en un flujo asincrono sano.
     */
    private static final Duration ANTELACION = Duration.ofSeconds(2);

    /** Intentos que se registran para un evento que fallo definitivamente. */
    private static final int INTENTOS_FALLIDOS = 5;

    private final NotificationEventRepositoryPort events;
    private final DeliveryAttemptRepositoryPort attempts;
    private final SubscriptionRepositoryPort subscriptions;
    private final ObjectMapper objectMapper;

    public DynamoDbDemoSeeder(
            NotificationEventRepositoryPort events,
            DeliveryAttemptRepositoryPort attempts,
            SubscriptionRepositoryPort subscriptions,
            ObjectMapper objectMapper) {
        this.events = events;
        this.attempts = attempts;
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void seed() {
        JsonNode raiz;
        try {
            raiz = objectMapper.readTree(new ClassPathResource(RECURSO).getInputStream());
        } catch (Exception e) {
            log.warn("No se pudo leer {}; no se siembra nada: {}", RECURSO, e.toString());
            return;
        }

        long sembrados = Flux.fromIterable(raiz)
                .concatMap(this::sembrarEvento)
                .filter(Boolean::booleanValue)
                .count()
                .block();

        log.info("Siembra de demostracion: {} notificaciones nuevas en DynamoDB", sembrados);
        sembrarSuscripciones();
    }

    /**
     * Suscripciones de los clientes de ejemplo, con comodin para todos los tipos.
     *
     * <p>Los secretos son fijos y estan en el repositorio a proposito: son de un entorno
     * local desechable y permiten verificar la firma en una demostracion sin tener que
     * copiarlos del alta. Ninguno vale fuera de aqui.
     */
    private void sembrarSuscripciones() {
        // Se escribe sin comprobar antes si existe. Consultar y despues escribir no es
        // atomico, y con los tres ejecutables arrancando a la vez los tres verian la
        // tabla vacia. La escritura va por clave fija y conserva el secreto con
        // if_not_exists, asi que repetirla converge al mismo item.
        long sembradas = Flux.fromIterable(CLIENTES_DEMO)
                .concatMap(clientId -> subscriptions.save(new Subscription(
                        UUID.nameUUIDFromBytes(clientId.getBytes(StandardCharsets.UTF_8)),
                        clientId,
                        Subscription.ALL_EVENT_TYPES,
                        "http://localhost:9090/webhooks/" + clientId,
                        "whsec_" + clientId.toLowerCase() + "_local_dev_secret",
                        true)))
                .count()
                .block();

        log.info("Siembra de demostracion: {} suscripciones aseguradas en DynamoDB", sembradas);
    }

    private Mono<Boolean> sembrarEvento(JsonNode nodo) {
        Instant entrega = Instant.parse(nodo.get("delivery_date").asString());
        DeliveryStatus estado = DeliveryStatus.valueOf(nodo.get("delivery_status").asString());
        String webhookUrl = "http://localhost:9090/webhooks/" + nodo.get("client_id").asString();

        NotificationEvent evento = new NotificationEvent(
                nodo.get("event_id").asString(),
                nodo.get("client_id").asString(),
                nodo.get("event_type").asString(),
                nodo.get("content").asString(),
                entrega.minus(ANTELACION),
                estado,
                entrega,
                estado == DeliveryStatus.FAILED ? INTENTOS_FALLIDOS : 1,
                0,
                webhookUrl,
                nodo.get("last_http_status").asInt(),
                nodo.get("last_error").isNull() ? null : nodo.get("last_error").asString(),
                entrega);

        return events.insertIfAbsent(evento)
                .flatMap(insertado -> insertado
                        ? bitacora(evento).thenReturn(true)
                        : Mono.just(false));
    }

    /** Bitacora coherente con el estado: un intento si se entrego, cinco si fallo. */
    private Mono<Void> bitacora(NotificationEvent evento) {
        List<DeliveryAttempt> registros = evento.deliveryStatus() == DeliveryStatus.FAILED
                ? intentosFallidos(evento)
                : List.of(intento(evento, 1, evento.deliveryDate(),
                        AttemptOutcome.DELIVERED, evento.lastHttpStatus(), 120, null));

        return Flux.fromIterable(registros).concatMap(attempts::append).then();
    }

    private List<DeliveryAttempt> intentosFallidos(NotificationEvent evento) {
        return java.util.stream.IntStream.rangeClosed(1, INTENTOS_FALLIDOS)
                .mapToObj(n -> intento(
                        evento,
                        n,
                        // Escalonados hacia atras desde la entrega, para que la bitacora
                        // se lea como un ciclo de reintentos y no como cinco a la vez.
                        evento.deliveryDate().minusSeconds((long) (INTENTOS_FALLIDOS - n) * 30),
                        AttemptOutcome.RETRYABLE_FAILURE,
                        evento.lastHttpStatus(),
                        5000,
                        evento.lastError()))
                .toList();
    }

    private DeliveryAttempt intento(
            NotificationEvent evento,
            int numero,
            Instant momento,
            AttemptOutcome desenlace,
            Integer httpStatus,
            long duracionMs,
            String error) {

        return new DeliveryAttempt(
                java.util.UUID.randomUUID(), evento.eventId(), numero, 0,
                momento, desenlace, httpStatus, duracionMs, error);
    }
}
