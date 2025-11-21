# Documentación Ejecutiva – Biblioteca Distribuidos

## Objetivo
Sistema de préstamos de biblioteca distribuido que protege disponibilidad replicando catálogos y préstamos en dos nodos (StorageReplica y StoragePrimary) usando mensajería ZeroMQ. Los actores de negocio (préstamo, devolución, renovación) enrutan por un gestor de carga; StoragePrimary es la fuente principal y StorageReplica actúa como réplica/backup.

## Componentes y roles
- `src/main/java/org/example/RequestProducer.java` (Productor de Solicitudes): CLI que envía peticiones de préstamo/devolución/renovación al Gestor de Carga. Opera en modo lectura de archivo `PS.txt` o entrada manual.
- `src/main/java/org/example/LoadBalancer.java` (Gestor de Carga): Punto de entrada de RequestProducer (REP en `tcp://localhost:5555`). Publica devoluciones/renovaciones por PUB (`tcp://*:5560`), reenvía préstamos al actor de préstamo vía REQ (`tcp://localhost:5556`).
- `src/main/java/org/example/LoanActor.java` (LoanActor): Recibe desde LoadBalancer (REP `tcp://*:5556`), consulta disponibilidad al StorageReplica (REQ `tcp://localhost:5557`) y si hay timeout/error cae a StoragePrimary (`tcp://localhost:5580`).
- `src/main/java/org/example/ReturnRenewalActor.java`: Suscriptor de devoluciones/renovaciones (SUB `tcp://localhost:5560`). Confirma en StorageReplica y si no responde, en StoragePrimary.
- `src/main/java/org/example/StorageReplica.java`: Réplica local de catálogo y préstamos. Atiende actores por REP (`tcp://*:5557`) y consume la base primaria StoragePrimary vía DEALER→ROUTER (`tcp://localhost:5570`). Mantiene `DB.txt` y `Prestamos.txt`.
- `src/main/java/org/example/StoragePrimary.java`: Fuente primaria. Expone ROUTER (`tcp://*:5570`) para GA, REP (`tcp://*:5580`) para actores en fallback y REQ a StorageReplica (`tcp://localhost:5557`) para notificaciones. Persiste `DB2.txt`, `Prestamos2.txt` y `CambiosPendientes.txt`.

## Puertos y patrones ZeroMQ
- `5555` REQ/REP: RequestProducer ↔ GC.
- `5560` PUB/SUB: LoadBalancer → ReturnRenewalActor (tópicos `DEVOLUCION` y `RENOVACION`).
- `5556` REQ/REP: LoadBalancer ↔ LoanActor.
- `5557` REP: StorageReplica para actores y para notificaciones de StoragePrimary.
- `5570` ROUTER/DEALER: StoragePrimary ↔ StorageReplica para sincronización de datos.
- `5580` REP: StoragePrimary para consultas directas de actores (fallback).

## Configuración por entorno (IPs/puertos)
Variables principales (todas tienen `localhost`/puerto por defecto):
- GC: `GC_BIND_HOST`, `GC_PS_PORT`, `GC_PUB_PORT`, `ACTOR_HOST`, `ACTOR_PORT`.
- PS: `GC_HOST`, `GC_PORT`.
- ActorPréstamo: `ACTOR_BIND_HOST`, `ACTOR_PORT`, `GA_HOST`, `GA_PORT`, `StoragePrimary_HOST`, `StoragePrimary_PORT`.
- ReturnRenewalActor: `GC_HOST`, `GC_PUB_PORT`, `GA_HOST`, `GA_PORT`, `StoragePrimary_HOST`, `StoragePrimary_PORT`.
- GA: `GA_BIND_HOST`, `GA_PORT`, `StoragePrimary_HOST`, `StoragePrimary_PORT`.
- StoragePrimary: `StoragePrimary_ROUTER_HOST`, `StoragePrimary_ROUTER_PORT`, `StoragePrimary_REP_HOST`, `StoragePrimary_REP_PORT`, `GA_HOST`, `GA_PORT`.

## Flujos de negocio
### Préstamo (`PRESTAMO:<ID>`)
1. RequestProducer envía a LoadBalancer (5555).
2. LoadBalancer reenvía al LoanActor (5556).
3. Actor consulta a StorageReplica con `Disponibilidad?<ID>`; si no responde en 3 s llama a StoragePrimary.
4. StoragePrimary marca `DB2.txt` PRESTADO, registra en `Prestamos2.txt` (fecha actual +14 días, veces=1), guarda cambio en `CambiosPendientes.txt` y notifica a GA.
5. Si StoragePrimary responde `SI`, StorageReplica aplica el mismo cambio a `DB.txt`/`Prestamos.txt` para mantener la réplica. Actor devuelve “Préstamo confirmado”.
6. Si el libro no existe o está PRESTADO, responde “NO …”.

### Devolución (`DEVOLVER <ID>`)
1. RequestProducer envía a GC; LoadBalancer publica `DEVOLUCION DEVOLVER:<ID>` en 5560.
2. ReturnRenewalActor reenvía vía REQ a StorageReplica (timeout 3 s → StoragePrimary).
3. StoragePrimary cambia estado a DISPONIBLE en `DB2.txt`, elimina entradas de `Prestamos2.txt`, loguea y notifica a GA. StorageReplica aplica el mismo cambio localmente.
4. Respuesta típica: “Devolución registrada exitosamente” o “Libro no está prestado”.

### Renovación (`RENOVAR <ID>`)
1. Flujo idéntico a devolución, pero con reglas de negocio:
   - Si libro está PRESTADO y `vecesPrestadas`=1 → extiende +7 días y marca 2.
   - Si `vecesPrestadas`=2 → rechazo (“No se pueden hacer más renovaciones”).
   - Si estado DISPONIBLE → se trata como nuevo préstamo (+14 días, veces=1).
2. StoragePrimary registra el cambio y lo replica a GA.

### Sincronización GA/StoragePrimary
- Al iniciar, StorageReplica consulta a StoragePrimary con `OBTENER_CAMBIOS`; recibe el log separado por `|`, aplica cada cambio en `DB.txt`/`Prestamos.txt` y confirma con `CAMBIOS_SINCRONIZADOS:...`.
- StoragePrimary conserva en `CambiosPendientes.txt` cualquier operación exitosa de préstamo/devolución/renovación. Solo elimina elementos cuando StorageReplica confirma.
- Además, StoragePrimary intenta notificar en tiempo real cada cambio (`NOTIFICACION_CAMBIO:<TIPO>:ID=<id>`) usando REQ hacia StorageReplica (5557); StorageReplica aplica el cambio local al recibir “OK”.
- Si StorageReplica está caído, el log persiste y se reenviará en la siguiente sincronización o cada 30 s por tarea periódica en GA.

## Archivos y formatos
- `DB.txt` / `DB2.txt`: `ID, TITULO, AUTOR, ESTADO` con estados `DISPONIBLE|PRESTADO`.
- `Prestamos.txt` / `Prestamos2.txt`: `ID, fechaPrestamo(YYYY-MM-DD), fechaDevolucion, vecesPrestadas`.
- `CambiosPendientes.txt`: log textual `YYYY-MM-DD HH:MM:SS, TIPO, ID=<id>`, usado para sincronización.
- `CambiosPendientesGA.txt`: solicitudes pendientes que StorageReplica procesó en modo degradado cuando StoragePrimary no respondió; se reenvían automáticamente cuando StoragePrimary vuelve.
- Rutas de GA/StoragePrimary usan paths relativos al directorio de ejecución (`DB*.txt`, `Prestamos*.txt`, `CambiosPendientes*.txt`).

## Reglas de negocio
- Solo se presta si el estado es `DISPONIBLE`.
- Un préstamo inicial dura 14 días; primera renovación suma 7 días; segunda renovación no permitida.
- Una devolución cambia estado a `DISPONIBLE` y elimina todas las filas del libro en `Prestamos*.txt`.
- Renovar un libro disponible lo trata como nuevo préstamo.
- Se usan strings simples; no hay control de concurrencia ni validaciones de usuario.

## Operación local sugerida
1. Ajustar rutas `RUTA_DB` y `RUTA_PRESTAMOS` en GA, y `RUTA_DB`, `RUTA_PRESTAMOS`, `RUTA_CAMBIOS_PENDIENTES` en StoragePrimary a rutas locales relativas al repo.
2. Construir: `./gradlew build`.
3. Levantar procesos (cada uno en terminal independiente, usando IDE o `java -cp build/libs/BIBLIOTECA_FINAL-1.0-SNAPSHOT.jar:~/.gradle/caches/.../jeromq-0.5.3.jar org.example.<Clase>`):
   - `StoragePrimary` (primario/log) → `StorageReplica` (réplica) → `LoanActor` → `ReturnRenewalActor` → `LoadBalancer` → `RequestProducer`.
4. En `RequestProducer`, elegir leer `PS.txt` (líneas `PRESTAMO 1`, `DEVOLVER 2`, `RENOVAR 3`…) o ingresar manual.
5. Revisar consola para trazas; los archivos `DB*.txt` y `Prestamos*.txt` reflejan el estado.

## Riesgos y pendientes
- Paths absolutos y dependencia en archivos planos; no se usa la dependencia PostgreSQL declarada.
- Sin control transaccional ni locks: ejecuciones concurrentes pueden pisar datos.
- Manejo rudimentario de errores y autenticación inexistente.
- Para producción se recomienda: parametrizar paths, mover persistencia a BD real, empaquetar scripts de arranque y añadir monitoreo de estados/healthcheck.
