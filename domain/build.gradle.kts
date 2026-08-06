plugins { `java-library` }

/*
 * El interior del hexagono: modelo, reglas de negocio y puertos.
 *
 * No declara ninguna dependencia mas alla de Reactor, que es una libreria de composicion
 * asincrona y no un framework de infraestructura. Es lo que hace verificable la regla de
 * la arquitectura hexagonal: aqui no se puede importar Spring porque no esta en el
 * classpath, asi que la violacion no compila.
 *
 * Se comparte entre los tres ejecutables a proposito. Los tres operan sobre las mismas
 * tablas y la misma maquina de estados; duplicar el modelo no daria independencia, daria
 * divergencia.
 */
dependencies {
    api("io.projectreactor:reactor-core")
}
