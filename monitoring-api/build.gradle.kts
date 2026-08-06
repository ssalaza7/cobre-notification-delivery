plugins { id("org.springframework.boot") }

/*
 * API self-service. Es el unico modulo con superficie HTTP entrante, y por eso el unico
 * que declara la cadena de seguridad y la validacion de peticiones.
 */
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Resource server JWT y emision de tokens propios.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.security:spring-security-test")
}
