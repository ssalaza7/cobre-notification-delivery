rootProject.name = "cobre-notification-delivery"

// Tres servicios desplegables y una libreria que comparten.
//
// Los sufijos dicen que es cada uno: -service produce jar ejecutable, imagen y
// contenedor propios; -lib no arranca y viaja dentro de los tres.
include(
    "cobre-notificacion-consumer-service",
    "cobre-notificacion-worker-service",
    "cobre-notificacion-api-service",
    "cobre-notificacion-kit-lib",
)
