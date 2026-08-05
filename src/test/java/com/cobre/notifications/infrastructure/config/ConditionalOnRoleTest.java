package com.cobre.notifications.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que un mismo artefacto pueda desplegarse como API, como worker o como ambos.
 *
 * <p>Es la prueba que sostiene la afirmacion del documento de diseno: "un microservicio,
 * dos roles de ejecucion". Sin esto, la separacion seria una promesa del documento que
 * el codigo no cumple.
 */
class ConditionalOnRoleTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RoleAwareBeans.class);

    @Test
    @DisplayName("sin configurar el rol arrancan ambos, que es lo comodo en desarrollo")
    void por_omision_arrancan_ambos() {
        runner.run(context -> {
            assertThat(context).hasBean("componenteApi");
            assertThat(context).hasBean("componenteWorker");
        });
    }

    @Test
    @DisplayName("el rol 'all' es equivalente a no configurar nada")
    void el_rol_all_activa_todo() {
        runner.withPropertyValues("cobre.role=all").run(context -> {
            assertThat(context).hasBean("componenteApi");
            assertThat(context).hasBean("componenteWorker");
        });
    }

    @Test
    @DisplayName("una instancia de API no levanta los consumidores del bus")
    void el_rol_api_excluye_al_worker() {
        runner.withPropertyValues("cobre.role=api").run(context -> {
            assertThat(context).hasBean("componenteApi");
            assertThat(context).doesNotHaveBean("componenteWorker");
        });
    }

    @Test
    @DisplayName("una instancia worker no expone los endpoints self-service")
    void el_rol_worker_excluye_a_la_api() {
        runner.withPropertyValues("cobre.role=worker").run(context -> {
            assertThat(context).hasBean("componenteWorker");
            assertThat(context).doesNotHaveBean("componenteApi");
        });
    }

    @Test
    @DisplayName("el rol se interpreta sin distinguir mayusculas ni espacios sobrantes")
    void tolera_el_formato_del_valor() {
        runner.withPropertyValues("cobre.role=  WORKER ").run(context -> {
            assertThat(context).hasBean("componenteWorker");
            assertThat(context).doesNotHaveBean("componenteApi");
        });
    }

    @Test
    @DisplayName("un rol desconocido no activa nada, en vez de activarlo todo por descuido")
    void un_rol_desconocido_no_activa_nada() {
        runner.withPropertyValues("cobre.role=cualquiera").run(context -> {
            assertThat(context).doesNotHaveBean("componenteApi");
            assertThat(context).doesNotHaveBean("componenteWorker");
        });
    }

    @Configuration
    @ConditionalOnProperty(name = "test.enabled", matchIfMissing = true)
    static class RoleAwareBeans {

        @Bean
        @ConditionalOnRole(ConditionalOnRole.API)
        String componenteApi() {
            return "api";
        }

        @Bean
        @ConditionalOnRole(ConditionalOnRole.WORKER)
        String componenteWorker() {
            return "worker";
        }
    }
}
