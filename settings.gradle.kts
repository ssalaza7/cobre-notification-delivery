plugins {
    // Resuelve (y descarga si hace falta) el JDK que pide la toolchain del build.
    // Sin esto, quien clone el repo necesita tener un JDK 21 en una ruta que Gradle
    // sepa escanear; con esto basta con tener Gradle y conexion.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "notification-delivery-service"
