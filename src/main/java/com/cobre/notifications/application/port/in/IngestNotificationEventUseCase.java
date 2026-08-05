package com.cobre.notifications.application.port.in;

import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Puerto de entrada: alta de un evento generado por la plataforma.
 *
 * <p>Lo invoca el consumidor del broker. Persiste el evento y lo encola para
 * entrega; no entrega en linea, para que la ingesta no dependa de la
 * disponibilidad del webhook del cliente.
 */
public interface IngestNotificationEventUseCase {

    Mono<IngestResult> ingest(IngestCommand command);

    record IngestCommand(
            String eventId,
            String clientId,
            String eventType,
            String content,
            Instant createdAt) {
    }

    enum IngestResult {
        /** Evento nuevo: persistido y encolado para entrega. */
        ACCEPTED,
        /** Ya existia ese event_id: no se vuelve a encolar. */
        DUPLICATE_IGNORED
    }
}
