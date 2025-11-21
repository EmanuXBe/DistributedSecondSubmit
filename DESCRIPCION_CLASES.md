# Descripción de clases – Biblioteca distribuida

## Utilidades y configuración
- `org.example.config.Config`
  - Lectura de variables de entorno con valor por defecto.
  - Agrupa hosts/puertos para GC, actores, GA (réplica) y GA2 (primario), además de rutas de archivos (`data/replica/*`, `data/primary/*`, `data/requests/requests.txt`).
  - Convierte rutas relativas a absolutas para evitar depender del cwd en tiempo de ejecución.

- `org.example.util.Console`
  - Logging mínimo a stdout/stderr con prefijo `[HH:mm:ss][TAG]`.
  - Niveles: `info`, `warn`, `error`.

- `org.example.util.CommandRouter`
  - Router simple de comandos string basado en predicados (`onPrefix`, `onExact`).
  - Evalúa en orden de registro; si ningún handler coincide devuelve una respuesta por defecto.

- `org.example.util.RequestParser`
  - Extrae ID numérico desde solicitudes (`<TIPO> <ID>` o `TIPO:ID`).
  - Prefiere el número después de `:`; si no existe, devuelve el primer token numérico encontrado.

## Front
- `org.example.front.RequestProducer`
  - Cliente REQ interactivo para el GC (`tcp://GC_HOST:GC_PS_PORT`, por defecto `localhost:6055`).
  - Dos modos: leer solicitudes desde `data/requests/requests.txt` o entrada manual por consola.
  - Normaliza las líneas a `TIPO:ID` (acepta separadores espacio/coma/`:`), envía al GC y muestra la respuesta de vuelta.
  - Gestiona el ciclo de vida de la conexión ZMQ y del `Scanner`.

- `org.example.front.LoadBalancer`
  - Gestor de carga (GC) que expone REP (`GC_PS_PORT`, default 6055) y PUB (`GC_PUB_PORT`, default 6060).
  - Router interno:
    - `PRESTAMO*` → reenvía por REQ a `LoanActor` (`ACTOR_HOST:ACTOR_PORT`, default `localhost:6056`).
    - `DEVOLVER*` → publica tópico `DEVOLUCION <payload>` en PUB.
    - `RENOVAR*` → publica tópico `RENOVACION <payload>` e incluye fecha de renovación (+1 semana) en la respuesta.
    - `PING` → `PONG`.
    - Otros → “Solicitud no reconocida”.
  - Mantiene sockets REP/PUB/REQ y cierra recursos al finalizar.

## Actores
- `org.example.actor.LoanActor`
  - Servidor REP (`ACTOR_BIND_HOST:ACTOR_PORT`, default `*:6056`) que recibe `PRESTAMO` desde el GC.
  - Extrae el ID vía `RequestParser`, construye `Disponibilidad?<ID>` y consulta a GA (`GA_HOST:GA_PORT`, default `localhost:6057`).
  - Si GA no responde, hace fallback a GA2 (`GA2_HOST:GA2_PORT`, default `localhost:6080`).
  - Traduce respuestas:
    - `SI` → “Préstamo confirmado”.
    - `NO ...` → “Préstamo rechazado: ...”.
    - Otros/errores → “Error: respuesta desconocida del GA”.

- `org.example.actor.ReturnRenewalActor`
  - Suscriptor SUB a los tópicos `DEVOLUCION` y `RENOVACION` en el PUB del GC (`GC_HOST:GC_PUB_PORT`, default `localhost:6060`).
  - Por cada evento, extrae ID y envía `DEVOLVER <ID>` o `RENOVAR <ID>` a GA (`6057`), con fallback a GA2 (`6080`) en caso de timeout/error.
  - Solo muestra en consola la respuesta de GA/GA2; no almacena estado local.

## Almacenamiento
- `org.example.storage.StorageReplica` (GA – réplica)
  - REP en `GA_BIND_HOST:GA_PORT` (default `*:6057`) para actores y notificaciones de GA2.
  - DEALER hacia GA2 (`GA2_HOST:GA2_ROUTER_PORT`, default `6070`) para reenviar todas las solicitudes y obtener confirmación del primario.
  - Router local (`localRouter`) permite operar en modo degradado si GA2 no responde: maneja disponibilidad, devoluciones y renovaciones contra `data/replica/books.csv` y `data/replica/loans.csv`.
  - Sincronización:
    - Al arranque: `OBTENER_CAMBIOS` a GA2; aplica cambios pendientes y confirma con `CAMBIOS_SINCRONIZADOS`.
    - Hilo periódico cada 30 s: repite sincronización y reenvía entradas de `data/replica/pending.log` (operaciones hechas en modo degradado).
    - Notificaciones en tiempo real: procesa `NOTIFICACION_CAMBIO:<TIPO>:ID=<id>` desde GA2 y replica el cambio local.
  - Persistencia: actualiza CSV para estados `DISPONIBLE/PRESTADO`; `loans.csv` gestiona fechas y contador `vecesPrestadas`. Escribe pendientes con UUID cuando GA2 está caído.

- `org.example.storage.StoragePrimary` (GA2 – primario)
  - ROUTER en `GA2_ROUTER_HOST:GA2_ROUTER_PORT` (default `*:6070`) para solicitudes de GA.
  - REP en `GA2_REP_HOST:GA2_REP_PORT` (default `*:6080`) para llamadas directas de actores (fallback).
  - REQ hacia GA (`GA_HOST:GA_PORT`, default `localhost:6057`) para notificar cambios.
  - Router de comandos: `Disponibilidad?`, `DEVOLVER`, `RENOVAR`, `PING`, `OBTENER_CAMBIOS`, `CAMBIOS_SINCRONIZADOS`.
  - Persistencia sobre `data/primary/books.csv` y `data/primary/loans.csv`; mantiene en memoria y en `data/primary/pending.log` una lista thread-safe (`CopyOnWriteArrayList`) de cambios pendientes.
  - Lógica de negocio:
    - Disponibilidad: marca `PRESTADO`, registra préstamo (+14 días, veces=1).
    - Devolución: marca `DISPONIBLE`, borra préstamos relacionados.
    - Renovación: si `vecesPrestadas=1` extiende +7 días y pasa a 2; si 2 → rechaza; si libro `DISPONIBLE` → trata como nuevo préstamo.
  - Sincronización: envía pendings a GA; marca como sincronizados al recibir `CAMBIOS_SINCRONIZADOS`. Intenta notificar cada cambio inmediatamente; si falla, permanece en el log para la siguiente ronda.
