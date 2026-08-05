package com.cobre.notifications.infrastructure.adapter.in.web;

import com.cobre.notifications.domain.exception.InvalidNotificationEventException;
import com.cobre.notifications.domain.exception.InvalidQueryException;
import com.cobre.notifications.domain.exception.NotificationEventNotFoundException;
import com.cobre.notifications.domain.exception.ReplayNotAllowedException;
import com.cobre.notifications.domain.model.DeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebInputException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("una notificacion inexistente o ajena responde 404")
    void traduce_no_encontrado() {
        ProblemDetail problem = handler.handleNotFound(new NotificationEventNotFoundException("EVT999"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Notificacion no encontrada");
        assertThat(problem.getType().toString()).endsWith("not-found");
    }

    @Test
    @DisplayName("reenviar algo que no esta en fallo definitivo responde 409")
    void traduce_conflicto_de_reenvio() {
        ProblemDetail problem = handler.handleReplayNotAllowed(
                new ReplayNotAllowedException("EVT001", DeliveryStatus.COMPLETED));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).contains("completed");
    }

    @Test
    @DisplayName("los criterios de consulta invalidos responden 400 explicando el problema")
    void traduce_consulta_invalida() {
        ProblemDetail problem = handler.handleInvalidQuery(new InvalidQueryException("size debe estar entre 1 y 100"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("size");
    }

    @Test
    @DisplayName("un evento con forma invalida responde 400")
    void traduce_evento_invalido() {
        ProblemDetail problem = handler.handleInvalidEvent(
                new InvalidNotificationEventException("event_id es obligatorio"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("un parametro mal tipado responde 400 con una pista del formato esperado")
    void traduce_parametro_mal_tipado() {
        ProblemDetail problem = handler.handleBadInput(new ServerWebInputException("fecha invalida"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("ISO-8601");
    }

    @Test
    @DisplayName("un error inesperado no filtra detalles internos, solo un identificador")
    void no_filtra_detalles_internos() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("connection to postgres://cobre:cobre@db:5432 failed"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail())
                .doesNotContain("postgres")
                .doesNotContain("cobre:cobre")
                .contains("identificador");
    }
}
