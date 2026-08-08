# Cómo se integra el trabajo

Dos reglas, y de ellas sale todo lo demás:

1. **Nada llega a `develop` ni a `main` por un empuje directo.** Solo por pull request.
2. **Un pull request se integra si las pruebas pasan y alguien lo aprueba.**

---

## Las ramas

| Rama | Qué contiene | De dónde recibe |
|---|---|---|
| `main` | Lo que está desplegado. Cada commit es una versión | `release/*`, `hotfix/*` |
| `develop` | Lo aceptado, a la espera de la próxima versión | `feature/*`, `bugfix/*` |
| `feature/*` | Un cambio en curso | — |
| `release/*` | Una versión que se está cerrando | `develop` |
| `hotfix/*` | Un arreglo urgente sobre lo desplegado | `main` |

El origen tiene que corresponder al destino: a `main` solo se llega desde `release/*` o
`hotfix/*`. Lo comprueba el workflow [Flujo de ramas](.github/workflows/gitflow.yml), porque la
protección de rama de GitHub obliga a pasar por pull request pero no mira de dónde viene.

---

## Un cambio, de principio a fin

```bash
# 1. Partir de develop al dia
git checkout develop && git pull
git checkout -b feature/lo-que-se-va-a-hacer

# 2. Trabajar y publicar la rama
git push -u origin feature/lo-que-se-va-a-hacer

# 3. Abrir el pull request contra develop
gh pr create --base develop --fill
```

A partir de ahí lo decide el pipeline: [Pruebas](.github/workflows/ci.yml) compila, ejecuta las
pruebas unitarias y verifica la cobertura. Deja el resultado como comentario en el pull request y
el informe HTML en los artefactos de la ejecución.

El umbral de cobertura —90 % de líneas y de instrucciones— vive en `build.gradle.kts`, no en el
workflow, para que el mismo número se aplique ejecutando `./gradlew build` en local. Bajar de ahí
falla la construcción antes de llegar a GitHub.

---

## Cerrar una versión

```bash
# 1. Cortar la version desde develop
git checkout develop && git pull
git checkout -b release/1.2.0
git push -u origin release/1.2.0

# 2. Pull request a main. Aqui solo entran correcciones de la propia version
gh pr create --base main --title "release 1.2.0" --fill

# 3. Una vez integrada, etiquetar
git checkout main && git pull
git tag -a v1.2.0 -m "1.2.0" && git push origin v1.2.0

# 4. Devolver a develop lo que se corrigio durante el cierre
gh pr create --base develop --head main --title "merge: 1.2.0 de vuelta a develop"
```

El último paso es el que se olvida: sin él, las correcciones hechas durante el cierre se pierden
en la siguiente versión.

Un `hotfix/*` es lo mismo, pero cortado desde `main` en vez de desde `develop`.

---

## Los mensajes de commit

Imperativo, en presente y explicando **por qué**, no qué. El qué ya está en el diff.

```
fix: la siembra asegura el secreto de demostracion en cada arranque

El emulador local de secretos no conserva nada entre arranques, mientras que
la tabla si tiene volumen. Un `down` y un `up` normales dejaban la suscripcion
apuntando a un secreto que ya no existia.
```

Prefijos en uso: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `merge`.
