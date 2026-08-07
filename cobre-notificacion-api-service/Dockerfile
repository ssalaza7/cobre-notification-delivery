# Construccion multietapa: la imagen final no lleva Gradle, ni el codigo fuente, ni el JDK.
#
# Se compila solo este modulo y sus dependencias (--no-daemon evita dejar un proceso
# Gradle colgado dentro de la capa de construccion).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Primero lo que casi nunca cambia: asi Docker reutiliza la capa de descarga de
# dependencias mientras solo se toque el codigo.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Se copian todos los modulos porque settings.gradle.kts los declara y Gradle exige que
# sus directorios existan, aunque esta imagen solo compile uno.
COPY cobre-notificacion-kit-lib cobre-notificacion-kit-lib
COPY cobre-notificacion-consumer-service cobre-notificacion-consumer-service
COPY cobre-notificacion-worker-service cobre-notificacion-worker-service
COPY cobre-notificacion-api-service cobre-notificacion-api-service

RUN ./gradlew --no-daemon :cobre-notificacion-api-service:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# Sin privilegios: un proceso comprometido no puede escribir en la imagen.
RUN useradd --system --uid 10001 cobre
USER cobre

COPY --from=build /src/cobre-notificacion-api-service/build/libs/cobre-notificacion-api-service-*-SNAPSHOT.jar app.jar

# Se lee del entorno para poder ajustar memoria sin reconstruir la imagen.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
