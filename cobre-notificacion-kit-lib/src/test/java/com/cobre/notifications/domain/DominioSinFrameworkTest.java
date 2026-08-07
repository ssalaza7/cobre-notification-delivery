package com.cobre.notifications.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El dominio no depende de ningun framework. Es la afirmacion central de la arquitectura
 * hexagonal, y esta prueba es lo que la sostiene.
 *
 * <p>Cuando el modelo vivia en un modulo aparte, la regla la imponia el compilador: Spring
 * no estaba en su classpath y el import no compilaba. Al unificar la libreria, esas
 * dependencias pasaron a estar disponibles, asi que la verificacion se hace aqui: se leen
 * los fuentes del paquete y se falla si aparece un import prohibido.
 *
 * <p>Reactor no esta en la lista a proposito. Es una libreria de composicion asincrona, no
 * un framework de infraestructura: no impone contenedor, ni ciclo de vida, ni configuracion.
 */
class DominioSinFrameworkTest {

    private static final List<String> PROHIBIDOS = List.of(
            "org.springframework",
            "software.amazon",
            "org.apache.kafka",
            "io.micrometer",
            "io.r2dbc",
            "jakarta.persistence",
            "com.fasterxml.jackson",
            "tools.jackson");

    private static final Path RAIZ = Path.of("src/main/java/com/cobre/notifications");

    private static List<String> importsProhibidos(Path archivo) throws IOException {
        List<String> hallazgos = new ArrayList<>();
        for (String linea : Files.readAllLines(archivo)) {
            if (!linea.startsWith("import ")) {
                continue;
            }
            PROHIBIDOS.stream()
                    .filter(linea::contains)
                    .forEach(p -> hallazgos.add(archivo.getFileName() + " -> " + linea.trim()));
        }
        return hallazgos;
    }

    private static List<String> revisar(String paquete) throws IOException {
        Path dir = RAIZ.resolve(paquete);
        assertThat(dir).as("el paquete %s debe existir", paquete).exists();

        List<String> hallazgos = new ArrayList<>();
        try (Stream<Path> archivos = Files.walk(dir)) {
            for (Path archivo : archivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                hallazgos.addAll(importsProhibidos(archivo));
            }
        }
        return hallazgos;
    }

    @Test
    @DisplayName("el modelo del negocio no importa ningun framework")
    void el_dominio_esta_limpio() throws IOException {
        assertThat(revisar("domain"))
                .as("el dominio debe poder compilarse y probarse sin framework alguno")
                .isEmpty();
    }

    @Test
    @DisplayName("los puertos tampoco: son contratos, y quien los implementa es la infraestructura")
    void los_puertos_estan_limpios() throws IOException {
        assertThat(revisar("application/port"))
                .as("un puerto que menciona una tecnologia deja de ser un contrato")
                .isEmpty();
    }
}
