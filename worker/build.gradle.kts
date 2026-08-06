plugins { id("org.springframework.boot") }

/*
 * Worker de entrega. Necesita el cliente HTTP reactivo porque es el unico modulo que
 * llama a terceros: cada entrega espera una respuesta que puede tardar segundos o no
 * llegar, y ahi es donde el modelo no bloqueante paga.
 *
 * WebFlux entra por el cliente, no por el servidor: este modulo no expone HTTP.
 */
dependencies {
    implementation(project(":kit"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
