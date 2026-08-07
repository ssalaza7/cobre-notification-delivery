plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.7" apply false
}

val springBootVersion = "4.0.7"
val awsSdkVersion = "2.31.78"
val kafkaClientsVersion = "3.9.1"

allprojects {
    group = "com.cobre"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        // Toolchain y no sourceCompatibility: Gradle localiza (o descarga) un JDK 21
        // aunque el demonio corra sobre otra version, asi la compilacion es
        // reproducible en cualquier maquina y en CI.
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    dependencies {
        val bom = platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        add("implementation", bom)
        add("testImplementation", bom)
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.mockito:mockito-core")
        add("testImplementation", "org.assertj:assertj-core")
        add("testImplementation", "io.projectreactor:reactor-test")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        }
    }
}

// Constantes que los modulos consumen desde su propio build.
extra["awsSdkVersion"] = awsSdkVersion
extra["kafkaClientsVersion"] = kafkaClientsVersion

/*
 * Gate de cobertura agregado sobre los cuatro modulos.
 *
 * Se mide el conjunto y no cada modulo por separado: la logica vive en `common` y los
 * ejecutables son sobre todo cableado, asi que un umbral por modulo penalizaria a los
 * tres ejecutables por algo que no es falta de pruebas sino reparto de responsabilidades.
 */
val coverageExclusions = listOf(
    "**/*Application.class",
    "**/infrastructure/config/**",
    "**/infrastructure/adapter/out/persistence/**",
)

val modulosCubiertos = subprojects

fun Project.clasesCubiertas() = files(
    modulosCubiertos.map { m ->
        m.extensions.getByType<SourceSetContainer>()["main"].output.classesDirs.map { dir ->
            fileTree(dir) { exclude(coverageExclusions) }
        }
    }
)

val jacocoReporteAgregado by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Cobertura agregada de los cuatro modulos"
    dependsOn(modulosCubiertos.map { it.tasks.named("test") })

    executionData.setFrom(files(modulosCubiertos.map { it.layout.buildDirectory.file("jacoco/test.exec") })
        .filter { it.exists() })
    sourceDirectories.setFrom(files(modulosCubiertos.map {
        it.extensions.getByType<SourceSetContainer>()["main"].allSource.srcDirs
    }))
    classDirectories.setFrom(clasesCubiertas())

    reports {
        html.required = true
        xml.required = true
    }
}

val jacocoGate by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Falla si la cobertura agregada baja del 90%"
    dependsOn(jacocoReporteAgregado)

    executionData.setFrom(files(modulosCubiertos.map { it.layout.buildDirectory.file("jacoco/test.exec") })
        .filter { it.exists() })
    sourceDirectories.setFrom(files(modulosCubiertos.map {
        it.extensions.getByType<SourceSetContainer>()["main"].allSource.srcDirs
    }))
    classDirectories.setFrom(clasesCubiertas())

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "INSTRUCTION"; value = "COVEREDRATIO"; minimum = "0.90".toBigDecimal()
            }
        }
    }
}

// El gate corre con `./gradlew build` en la raiz, no solo cuando se pide aparte.
tasks.named("build") { dependsOn(jacocoGate) }
