# Casos de uso – Biblioteca distribuida

## Visión general del sistema
- Front: `RequestProducer` (cliente interactivo o por archivo) → `LoadBalancer` (GC) enruta a actores.
- Actores: `LoanActor` procesa préstamos vía REQ/REP; `ReturnRenewalActor` consume devoluciones/renovaciones vía PUB/SUB.
- Persistencia: `StoragePrimary` (GA2, primario) y `StorageReplica` (GA, réplica). Ambos manipulan archivos CSV (`books.csv`, `loans.csv`) y llevan bitácoras de pendientes (`pending.log`).
- Defaults de red (configurables por variables de entorno): GC REQ `6055`, GC PUB `6060`, Actor `6056`, GA `6057`, GA2 ROUTER `6070`, GA2 REP `6080`.
- Formatos clave: solicitudes del cliente `PRESTAMO|DEVOLVER|RENOVAR <ID>`; mensaje interno de disponibilidad `Disponibilidad?<ID>`; cambios persistidos en log como `YYYY-MM-DD HH:mm:ss, TIPO, ID=<id>`.

## UC0 – Captura de solicitudes desde el cliente
- Objetivo: permitir que un operador dispare casos de negocio sin interactuar con nodos internos.
- Flujo: `RequestProducer` pregunta si leer `data/requests/requests.txt` o recibir entrada manual; cada línea se normaliza a `TIPO:ID` y se envía por REQ al GC (`tcp://GC_HOST:6055` por defecto).
- Resultados: recibe y muestra la respuesta textual devuelta por el GC; la conexión se cierra limpiamente tras finalizar la sesión.

## UC1 – Registrar préstamo de un libro (`PRESTAMO <ID>`)
- Actores involucrados: RequestProducer → LoadBalancer → LoanActor → GA (réplica) → GA2 (primario) → GA (para sincronía).
- Flujo nominal:
  1. GC recibe `PRESTAMO:ID` y lo reenvía al `LoanActor` vía REQ/REP en `6056`.
  2. `LoanActor` genera `Disponibilidad?<ID>` y consulta a GA (`6057`). Timeout 3 s → fallback a GA2 (`6080`).
  3. GA reenvía a GA2 con DEALER/ROUTER (`6070`). GA2 valida que `books.csv` tenga el ID y estado `DISPONIBLE`.
  4. Si hay disponibilidad, GA2 marca el libro `PRESTADO` en `data/primary/books.csv`, agrega entrada a `data/primary/loans.csv` (fecha actual, +14 días, veces=1), registra el cambio en `data/primary/pending.log` y notifica a GA (`NOTIFICACION_CAMBIO:PRESTAMO:ID=<id>`).
  5. GA aplica el mismo cambio a su copia (`data/replica/books.csv` y `loans.csv`) y responde `SI` a GA2/Actor.
  6. `LoanActor` traduce `SI` a “Préstamo confirmado”; cualquier `NO` se propaga como rechazo.
- Variantes y errores: libro inexistente → “NO (libro no existe)”; libro ya prestado → “NO (ya está prestado)”; si ni GA ni GA2 responden → error genérico al actor.

## UC2 – Registrar devolución (`DEVOLVER <ID>`)
- Actores: RequestProducer → GC (PUB) → ReturnRenewalActor (SUB) → GA → GA2 → GA.
- Flujo nominal:
  1. GC publica `DEVOLUCION DEVOLVER:ID` en `6060`.
  2. `ReturnRenewalActor` extrae el ID y envía `DEVOLVER <ID>` a GA (`6057`); si hay timeout, usa GA2 (`6080`) como fallback.
  3. GA envía primero a GA2. GA2 busca el libro: si está `PRESTADO`, lo marca `DISPONIBLE` en `data/primary/books.csv`, elimina cualquier línea con ese ID en `data/primary/loans.csv`, registra el cambio en `pending.log` y notifica a GA.
  4. GA sincroniza el mismo cambio en su copia (`books.csv` a `DISPONIBLE`, borra préstamos) y responde “Devolución registrada exitosamente”.
- Variantes: libro no prestado → “Libro no está prestado”; libro inexistente → “Error: Libro no encontrado”; si GA2 no responde, GA procesa local y deja el cambio en `data/replica/pending.log` para reintentar luego.

## UC3 – Renovar préstamo (`RENOVAR <ID>`)
- Actores: RequestProducer → GC (PUB) → ReturnRenewalActor (SUB) → GA → GA2 → GA.
- Flujo nominal:
  1. GC publica `RENOVACION RENOVAR:ID`; el actor envía `RENOVAR <ID>` a GA (o GA2 si timeout).
  2. GA reenvía a GA2. GA2 localiza el libro y su préstamo en `loans.csv`:
     - Si estado `PRESTADO` y `vecesPrestadas=1`: suma 7 días desde hoy, actualiza `vecesPrestadas` a 2.
     - Si `vecesPrestadas=2`: rechaza con “No se pueden hacer más renovaciones (máximo 2)”.
     - Si el libro está `DISPONIBLE`: se trata como nuevo préstamo (mismo flujo que UC1, +14 días y `vecesPrestadas=1`).
  3. Cambios exitosos se loguean en `data/primary/pending.log` y se notifican a GA, que replica en su CSV.
- Variantes: préstamo no encontrado → “Error: No se encontró registro de préstamo”; libro inexistente → “Error: Libro no encontrado”; fallo de GA2 → GA procesa local, guarda el pendiente y reenviará cuando GA2 vuelva.

## UC4 – Sincronización entre primario (GA2) y réplica (GA)
- Arranque de GA: envía `OBTENER_CAMBIOS` a GA2 (ROUTER `6070`). GA2 responde con cambios pendientes separados por `|` o `SIN_CAMBIOS`. GA aplica cada cambio (préstamo/devolución/renovación) a `data/replica/*.csv` y confirma con `CAMBIOS_SINCRONIZADOS:<cambios>`, tras lo cual GA2 depura su `pending.log`.
- Notificaciones en tiempo real: cada operación exitosa en GA2 intenta enviar `NOTIFICACION_CAMBIO:<TIPO>:ID=<id>` por REQ hacia GA (`6057`). GA aplica el cambio al vuelo y sigue operando aunque la notificación no llegue.
- Sincronización periódica: GA ejecuta cada 30 s un ciclo `OBTENER_CAMBIOS` + reenvío de pendientes (UC5) para ponerse al día si perdió notificaciones.

## UC5 – Modo degradado y reenvío diferido
- GA sin GA2: si `enviarAGa2` falla, GA procesa la solicitud con su `localRouter`, actualiza `data/replica/*.csv` y registra la operación con UUID en `data/replica/pending.log`. Un hilo periódico intenta reenviar esas entradas a GA2 y, si se confirman, limpia el log.
- GA caído: los actores usan directamente GA2 (`6080`) para mantener el servicio de negocio. La réplica no verá los cambios hasta que reciba una notificación o hasta la siguiente sincronización (UC4).
- GA2 caído: los actores aún pueden operar contra GA (si GC sigue vivo), pero los cambios quedan pendientes hasta que GA2 vuelva.

## UC6 – Verificación de salud
- Mensajes `PING` retornan `PONG` en GC, GA y GA2, permitiendo comprobar conectividad básica de cada nodo antes de procesar solicitudes reales.
