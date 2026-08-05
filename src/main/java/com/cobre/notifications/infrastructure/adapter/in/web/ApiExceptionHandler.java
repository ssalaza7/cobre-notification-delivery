package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.domain.exception.InvalidNotificationEventException;
import com.cobre.notifications.domain.exception.InvalidQueryException;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.exception.ReplayNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;
import java.util.UUID;

/**
 * Traduce los errores a respuestas RFC 7807.
 *
 * <p>Los mensajes de dominio son especificos porque describen algo que el cliente
 * puede corregir. El manejador generico, en cambio, no expone nada: devuelve un
 * identificador de correlacion y deja el detalle en los logs. Una traza o un mensaje
 * de driver filtrado en la respuesta le regala al atacante el mapa del sistema
 * (OWASP A05).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String BASE_TYPE = "https://docs.cobre.co/errors/";

    @ExceptionHandler(NotificationEventNotFoundException.class)
    public ProblemDetail handleNotFound(NotificationEventNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Notificacion no encontrada", e.getMessage(), "not-found");
    }

    @ExceptionHandler(ReplayNotAllowedException.class)
    public ProblemDetail handleReplayNotAllowed(ReplayNotAllowedException e) {
        return problem(HttpStatus.CONFLICT, "Reenvio no permitido", e.getMessage(), "replay-not-allowed");
    }

    @ExceptionHandler(InvalidQueryException.class)
    public ProblemDetail handleInvalidQuery(InvalidQueryException e) {
        return problem(HttpStatus.BAD_REQUEST, "Consulta invalida", e.getMessage(), "invalid-query");
    }

    @ExceptionHandler(InvalidNotificationEventException.class)
    public ProblemDetail handleInvalidEvent(InvalidNotificationEventException e) {
        return problem(HttpStatus.BAD_REQUEST, "Evento invalido", e.getMessage(), "invalid-event");
    }

    /** Parametro mal tipado, por ejemplo una fecha que no es ISO-8601. */
    @ExceptionHandler(ServerWebInputException.class)
    public ProblemDetail handleBadInput(ServerWebInputException e) {
        return problem(HttpStatus.BAD_REQUEST, "Parametros invalidos",
                "Revise el formato de los parametros de la peticion; las fechas deben ir en ISO-8601 "
                        + "(por ejemplo 2024-03-15T00:00:00Z)",
                "invalid-parameters");
    }

    /**
     * Errores que el propio framework ya clasifico con un estado HTTP: una ruta que no
     * existe, un metodo no permitido, un tipo de contenido no soportado.
     *
     * <p>Sin este manejador caerian en el generico de abajo y saldrian como 500. Una
     * ruta inexistente no es un fallo del servidor, y responder 500 ademas oculta el
     * problema real a quien esta integrando.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return problem(status, status.getReasonPhrase(),
                e.getReason() != null ? e.getReason() : "La peticion no pudo atenderse",
                "request-rejected");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Error no controlado [correlationId={}]", correlationId, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Ocurrio un error inesperado. Reporte el identificador " + correlationId,
                "internal-error");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE_TYPE + type));
        return problem;
    }
}
