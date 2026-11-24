# Proyecto 04 – Web, API y Microservicios

Aplicación web basada en **microservicios Spring Boot (Java 17)** con **API REST** (POST, GET, PUT, DELETE), persistencia en **PostgreSQL**, empaquetada con **Docker** y desplegable en **Kubernetes**, con pipeline **CI/CD en GitHub Actions** (pruebas, imágenes en **GHCR** y despliegue con runner Windows propio) y seguridad **JWT firmado con RSA (RS256)** y **OAuth 2.0 (Resource Server)**.

## Arquitectura

```
                    ┌──────────────────────┐
  POST /api/auth/*  │     auth-service     │   JWKS (llave pública)
 ─────────────────► │  :8081  (emite JWT)  │ ◄───────────────┐
                    └──────────┬───────────┘                 │
                               │                             │ valida firma RS256
                               ▼                             │
                    ┌──────────────────────┐     ┌───────────┴──────────┐
                    │ PostgreSQL  (authdb) │     │     task-service     │ ◄── GET/POST/PUT/DELETE
                    │            (taskdb)  │ ◄───┤ :8082 (Resource Srv) │     /api/tasks  (Bearer JWT)
                    └──────────────────────┘     └──────────────────────┘
```

| Servicio | Puerto | Rol |
|---|---|---|
| `auth-service` | 8081 | Registro, login (emite JWT RS256) y publicación de la llave pública en `/.well-known/jwks.json`. Contraseñas con BCrypt. |
| `task-service` | 8082 | CRUD de tareas. OAuth 2.0 Resource Server: valida cada JWT (firma, expiración, issuer) contra el JWKS de `auth-service`. |
| `postgres` | 5432 | Una base de datos por microservicio (`authdb`, `taskdb`). |
| `frontend` | 80 (8080 en Compose) | Página web (HTML + JS) servida por nginx. Reenvía `/api/auth/*` a `auth-service` y `/api/tasks*` a `task-service`, por lo que el navegador solo habla con un origen (sin CORS). |

## Cumplimiento de la rúbrica (Proyecto 04)

| # | Criterio | Dónde está |
|---|---|---|
| 1 | Pipeline de compilación, pruebas y publicación de imágenes en GHCR | `.github/workflows/ci-cd.yml` (jobs `test` y `build-and-push`); pruebas en `*/src/test` |
| 2 | Despliegue en Kubernetes mediante un runner Windows propio | job `deploy` (`runs-on: [self-hosted, windows]`) + `scripts/deploy-k8s.ps1` + `k8s/` |
| 3 | Proyecto hospedado en GitHub por cada estudiante | ver «Publicar en GitHub» |

Base técnica: microservicios Spring Boot con API REST (POST, GET, PUT, DELETE), PostgreSQL, Docker por servicio, Kubernetes, **JWT RS256** (llave RSA 2048) y **OAuth 2.0** (`task-service` es Resource Server que valida contra el JWKS de `auth-service`), roles `USER`/`ADMIN`, BCrypt y sesiones stateless.

## CI/CD con GitHub Actions

```
push / PR a main
   │
   ├─ test            (matriz auth-service, task-service)  mvn verify con JUnit 5, MockMvc, Mockito y H2
   ├─ build-and-push  (matriz auth-service, task-service, frontend)
   │                  construye la imagen; en push a main la publica en ghcr.io/<usuario>/<servicio>:<sha> y :latest
   └─ deploy          solo en push a main, en el runner Windows propio: kubectl apply en el namespace `microservicios`
```

- En los *pull requests* se compila, se prueba y se construyen las imágenes, pero **no** se publica ni se despliega.
- Las imágenes se etiquetan con el SHA del commit; el despliegue usa ese SHA, así cada release es trazable.

### Pruebas

```bash
cd auth-service && mvn verify    # registro, login, firma RS256, JWKS, rechazo de otra llave, admin sembrado
cd task-service && mvn verify    # reglas de servicio (Mockito) + API REST completa con seguridad (MockMvc)
```

Usan H2 en memoria (perfil `test`), no requieren PostgreSQL ni Docker.

### Configurar el runner Windows propio

1. En tu repositorio: **Settings → Actions → Runners → New self-hosted runner → Windows** y sigue los comandos (`config.cmd`, luego `run.cmd`). Deja las etiquetas por defecto (`self-hosted`, `windows`).
2. En esa misma máquina deben estar instalados: **kubectl** y un clúster accesible (Docker Desktop con Kubernetes activado, minikube o kind), y **openssl** (viene con Git for Windows) si no vas a definir las llaves como secrets.
3. Si instalas el runner como servicio de Windows, asegúrate de que la cuenta del servicio tenga el `kubeconfig` (`%USERPROFILE%\.kube\config`) del clúster; si no, ejecútalo en una consola con `run.cmd`.
4. Comprueba desde esa consola: `kubectl cluster-info`.

### Secrets del repositorio (Settings → Secrets and variables → Actions)

| Secret | Obligatorio | Uso |
|---|---|---|
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | No | Contenido de `keys/private.pem` y `keys/public.pem` (PKCS#8 / X.509). Si no existen y el clúster aún no tiene el secret `jwt-keys`, el despliegue genera las llaves con openssl. |
| `GHCR_PULL_TOKEN` | Solo si las imágenes son privadas | PAT con `read:packages`; se guarda en el clúster como `ghcr-pull`. |

`GITHUB_TOKEN` (automático) basta para publicar en GHCR. Tras el primer *push*, en **Packages** puedes poner las imágenes como públicas para que el clúster las descargue sin credenciales.

### Despliegue manual

```powershell
./scripts/deploy-k8s.ps1 -Registry ghcr.io/<usuario-en-minusculas> -Tag <sha-o-latest>
```

`scripts/deploy-k8s.sh` sigue disponible para probar en local con imágenes construidas en tu máquina.

## Requisitos

Docker (con Compose). Para Kubernetes: `kubectl` + minikube, kind o Docker Desktop con Kubernetes. Para compilar sin Docker: JDK 17 y Maven.

## Ejecución con Docker Compose

```bash
docker compose up --build
```

Abre la página web en **http://localhost:8080**: crea una cuenta, inicia sesión y gestiona tus tareas (crear, editar, completar, borrar).

> En este modo `auth-service` genera un par RSA nuevo al arrancar; si lo reinicias, los tokens anteriores dejan de ser válidos. En Kubernetes las llaves vienen de un Secret y son estables.

## Ejecución en Kubernetes

```bash
./scripts/deploy-k8s.sh
```

El script construye las imágenes, genera las llaves RSA (`keys/`, ignoradas por Git), crea el Secret `jwt-keys` y aplica los manifiestos de `k8s/`. La página web queda en NodePort `30080` y los servicios en `30081` (auth) y `30082` (tasks). Si no puedes alcanzar los NodePort, deja esto corriendo en una terminal y abre **http://localhost:8080**:

```bash
kubectl port-forward -n microservicios svc/frontend 8080:80
```

Para probar las APIs directamente con `curl` (sección siguiente) también puedes exponerlas:

```bash
kubectl port-forward -n microservicios svc/auth-service 8081:8081
kubectl port-forward -n microservicios svc/task-service 8082:8082
```

Escalar: `kubectl scale deployment/task-service --replicas=2 -n microservicios` (haz el escalado después de que el primer pod haya arrancado, porque Hibernate crea las tablas al inicio).

## Probar la API

```bash
# 1) Registrar usuario
curl -X POST localhost:8081/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"maria","password":"Password123"}'

# 2) Login -> token
TOKEN=$(curl -s -X POST localhost:8081/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"maria","password":"Password123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

# 3) CRUD de tareas
curl -X POST   localhost:8082/api/tasks -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Entregar proyecto 04","description":"Subir enlace de GitHub"}'
curl           localhost:8082/api/tasks -H "Authorization: Bearer $TOKEN"
curl -X PUT    localhost:8082/api/tasks/1 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Entregar proyecto 04","description":"Listo","completed":true}'
curl -X DELETE localhost:8082/api/tasks/1 -H "Authorization: Bearer $TOKEN"

# Sin token -> 401
curl -i localhost:8082/api/tasks
```

Usuario administrador inicial: `admin` / `Admin12345!` (configurable con `ADMIN_USERNAME` y `ADMIN_PASSWORD`; cámbialo fuera de desarrollo).

### Endpoints

| Servicio | Método | Ruta | Acceso |
|---|---|---|---|
| auth | POST | `/api/auth/register` | público |
| auth | POST | `/api/auth/login` | público |
| auth | GET | `/.well-known/jwks.json` | público (llave pública) |
| tasks | GET | `/api/tasks`, `/api/tasks/{id}` | Bearer JWT |
| tasks | POST | `/api/tasks` | Bearer JWT |
| tasks | PUT | `/api/tasks/{id}` | Bearer JWT (dueño o ADMIN) |
| tasks | DELETE | `/api/tasks/{id}` | Bearer JWT (dueño o ADMIN) |

## Variables de entorno principales

| Variable | Servicio | Descripción |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | ambos | Conexión a PostgreSQL |
| `JWT_ISSUER` | ambos | Debe coincidir en los dos servicios |
| `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH` | auth | PEM PKCS#8 / X.509. Si se omiten, se genera un par efímero |
| `JWT_TTL_SECONDS` | auth | Vigencia del token (3600 por defecto) |
| `JWT_JWKS_URI` | tasks | URL del JWKS de auth-service |

## Estructura

```
├── auth-service/        # Spring Boot + Dockerfile + pruebas
├── task-service/        # Spring Boot + Dockerfile + pruebas
├── frontend/            # Página web + nginx + Dockerfile
├── .github/workflows/   # ci-cd.yml (pruebas, GHCR, despliegue)
├── k8s/                 # Manifiestos de Kubernetes
├── scripts/             # deploy-k8s.ps1 (CI/CD), deploy-k8s.sh y generate-keys.sh (local)
├── db/init.sql          # Crea authdb y taskdb
└── docker-compose.yml
```

## Publicar en GitHub

Cada integrante debe tener el proyecto en **su propio** repositorio:

```bash
git init
git add .
git commit -m "Proyecto 04: CI/CD con GitHub Actions, GHCR y Kubernetes"
git branch -M main
git remote add origin https://github.com/<tu-usuario>/<tu-repositorio>.git
git push -u origin main
```

Las llaves (`keys/`) y archivos `.env` están en `.gitignore`: no los subas. Al subir el código, el workflow se ejecuta solo (pestaña **Actions**); el job `deploy` quedará en espera hasta que el runner Windows esté en línea.
# taller_3_web_apis
# ultima-entrega-web-apis
