package org.example.storage;

import org.example.config.Config;
import org.example.util.CommandRouter;
import org.example.util.Console;
import org.zeromq.ZMQ;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StoragePrimary {

    private static final String UNKNOWN_RESPONSE = "Solicitud no reconocida";
    private static final String GA2_ROUTER_HOST = Config.env("GA2_ROUTER_HOST", "*");
    private static final String GA2_ROUTER_PORT = Config.ga2RouterPort();
    private static final String GA2_REP_HOST = Config.env("GA2_REP_HOST", "*");
    private static final String GA2_REP_PORT = Config.ga2RepPort();
    private static final String GA_HOST = Config.gaHost(); // Puerto del GA para notificaciones
    private static final String GA_PORT = Config.gaPort(); // Puerto del GA para notificaciones
    private static final String BOOK_DB_PATH = Config.primaryBookDbPath();
    private static final String LOANS_PATH = Config.primaryLoansPath();
    private static final String PENDING_LOG_PATH = Config.primaryPendingLogPath();
    
    // Lista en memoria para cambios pendientes (thread-safe)
    private final CopyOnWriteArrayList<String> cambiosPendientes = new CopyOnWriteArrayList<>();
    private final CommandRouter router = new CommandRouter(UNKNOWN_RESPONSE)
        .onExact("PING", req -> "PONG")
        .onPrefix("Disponibilidad?", this::handleAvailability)
        .onPrefix("DEVOLVER", this::handleReturn)
        .onPrefix("RENOVAR", this::handleRenewal)
        .onExact("OBTENER_CAMBIOS", req -> handleRequestSync())
        .onPrefix("CAMBIOS_SINCRONIZADOS:", this::procesarCambiosSincronizados);
    private ZMQ.Context contextNotificacion;
    private ZMQ.Socket socketNotificacion;

    public static void main(String[] args) {
        new StoragePrimary().iniciar();
    }

    public void iniciar() {
        ZMQ.Context context = ZMQ.context(1);
        
        // Crear socket ROUTER para comunicación con GA (puerto 5570)
        ZMQ.Socket router = context.socket(ZMQ.ROUTER);
        router.bind("tcp://" + GA2_ROUTER_HOST + ":" + GA2_ROUTER_PORT);
        Console.info("GA2", "ROUTER en tcp://" + GA2_ROUTER_HOST + ":" + GA2_ROUTER_PORT);
        
        // Crear socket REP para comunicación directa con actores (puerto 5580)
        ZMQ.Socket rep = context.socket(ZMQ.REP);
        rep.bind("tcp://" + GA2_REP_HOST + ":" + GA2_REP_PORT);
        Console.info("GA2", "REP en tcp://" + GA2_REP_HOST + ":" + GA2_REP_PORT);
        
        // Inicializar socket para notificaciones a GA
        inicializarSocketNotificacion();
        
        // Cargar cambios pendientes desde archivo si existe
        cargarCambiosPendientes();
        
        // Thread para manejar solicitudes del ROUTER (desde GA)
        Thread threadRouter = new Thread(() -> manejarSolicitudesRouter(context, router));
        threadRouter.setDaemon(true);
        threadRouter.start();
        
        // Thread para manejar solicitudes del REP (directo de actores)
        Thread threadRep = new Thread(() -> manejarSolicitudesRep(context, rep));
        threadRep.setDaemon(true);
        threadRep.start();
        
        // Mantener el hilo principal vivo
        try {
            threadRouter.join();
            threadRep.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        router.close();
        rep.close();
        if (socketNotificacion != null) {
            socketNotificacion.close();
        }
        if (contextNotificacion != null) {
            contextNotificacion.term();
        }
        // Guardar cambios pendientes antes de cerrar
        guardarCambiosPendientes();
        context.term();
    }
    
    // Inicializa socket para enviar notificaciones a GA
    private void inicializarSocketNotificacion() {
        try {
            contextNotificacion = ZMQ.context(1);
            socketNotificacion = contextNotificacion.socket(ZMQ.REQ);
            socketNotificacion.connect("tcp://" + GA_HOST + ":" + GA_PORT);
            socketNotificacion.setReceiveTimeOut(1000); // Timeout corto para no bloquear
            System.out.println(" GA2: Socket de notificación conectado a GA en " + GA_HOST + ":" + GA_PORT);
        } catch (Exception e) {
            System.err.println(" GA2: Error al inicializar socket de notificación: " + e.getMessage());
            socketNotificacion = null;
        }
    }
    
    // Carga cambios pendientes desde archivo
    private void cargarCambiosPendientes() {
        try {
            File archivo = new File(PENDING_LOG_PATH);
            if (archivo.exists()) {
                List<String> lineas = Files.readAllLines(Paths.get(PENDING_LOG_PATH), StandardCharsets.UTF_8);
                cambiosPendientes.addAll(lineas);
                System.out.println(" GA2: Cargados " + cambiosPendientes.size() + " cambios pendientes desde archivo");
            }
        } catch (Exception e) {
            System.err.println(" GA2: Error al cargar cambios pendientes: " + e.getMessage());
        }
    }
    
    // Guarda cambios pendientes a archivo
    private void guardarCambiosPendientes() {
        try {
            Files.write(Paths.get(PENDING_LOG_PATH), cambiosPendientes, StandardCharsets.UTF_8);
            System.out.println(" GA2: Guardados " + cambiosPendientes.size() + " cambios pendientes en archivo");
        } catch (Exception e) {
            System.err.println(" GA2: Error al guardar cambios pendientes: " + e.getMessage());
        }
    }
    
    // Registra un cambio en el log
    private void registerChange(String tipoOperacion, String datos) {
        try {
            LocalDateTime ahora = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String timestamp = ahora.format(formatter);
            String cambio = timestamp + ", " + tipoOperacion + ", " + datos;
            
            cambiosPendientes.add(cambio);
            System.out.println(" GA2: Cambio registrado en log: " + cambio);
            
            // Guardar inmediatamente en archivo para persistencia
            guardarCambiosPendientes();
        } catch (Exception e) {
            System.err.println(" GA2: Error al registrar cambio: " + e.getMessage());
        }
    }
    
    // Intenta enviar notificación a GA (no bloqueante)
    private void enviarNotificacionAGa(String tipoOperacion, String datos) {
        if (socketNotificacion == null) {
            return; // Socket no disponible, cambio quedará en log
        }
        
        try {
            String notificacion = "NOTIFICACION_CAMBIO:" + tipoOperacion + ":" + datos;
            socketNotificacion.send(notificacion, 0);
            String respuesta = socketNotificacion.recvStr();
            
            if (respuesta != null && respuesta.equals("OK")) {
                System.out.println(" GA2: Notificación enviada exitosamente a GA: " + tipoOperacion);
                // No eliminamos el cambio del log aún, esperamos confirmación explícita
            } else {
                System.out.println(" GA2: GA no confirmó notificación, cambio quedará en log");
            }
        } catch (Exception e) {
            System.out.println(" GA2: Error al enviar notificación a GA (GA puede estar caído): " + e.getMessage());
            // Cambio quedará en log para sincronización posterior
        }
    }
    
    // Obtiene lista de cambios pendientes
    private List<String> obtenerCambiosPendientes() {
        return new ArrayList<>(cambiosPendientes);
    }
    
    // Marca cambios como sincronizados (los elimina del log)
    private void marcarCambioSincronizado(List<String> cambiosSincronizados) {
        try {
            cambiosPendientes.removeAll(cambiosSincronizados);
            guardarCambiosPendientes();
            System.out.println(" GA2: " + cambiosSincronizados.size() + " cambios marcados como sincronizados");
        } catch (Exception e) {
            System.err.println(" GA2: Error al marcar cambios como sincronizados: " + e.getMessage());
        }
    }
    
    // Maneja solicitudes del ROUTER (desde GA)
    private void manejarSolicitudesRouter(ZMQ.Context context, ZMQ.Socket router) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Recibir formato ROUTER: [identidad] [mensaje]
            Console.info("GA2-ROUTER", "Esperando solicitud...");
            String identidad = router.recvStr();
            Console.info("GA2-ROUTER", "Identidad: " + identidad);
                
                // Verificar si hay más frames
                String siguiente = router.recvStr();
                Console.info("GA2-ROUTER", "Frame 2: '" + siguiente + "'");
                
                String solicitud;
                // Si el siguiente frame está vacío, el mensaje está en el tercer frame
                // Si no está vacío, ese es el mensaje
                if (siguiente == null || siguiente.isEmpty()) {
                    Console.warn("GA2-ROUTER", "Frame vacío, leyendo cuerpo...");
                    solicitud = router.recvStr();
                } else {
                    solicitud = siguiente;
                }
                Console.info("GA2-ROUTER", "Solicitud: " + solicitud);

                String respuesta = handleRequest(solicitud);
                Console.info("GA2-ROUTER", "Respuesta: " + respuesta);
            
                // Enviar respuesta en formato ROUTER: [identidad] [respuesta]
                router.send(identidad, ZMQ.SNDMORE); // Identidad del DEALER
                router.send(respuesta, 0);            // Respuesta
                Console.info("GA2-ROUTER", "Respuesta enviada");
                
            } catch (Exception e) {
                Console.error("GA2-ROUTER", "Error: " + e.getMessage());
            }
        }
    }
    
    // Maneja solicitudes del REP (directo de actores)
    private void manejarSolicitudesRep(ZMQ.Context context, ZMQ.Socket rep) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Recibir solicitud del REP (formato simple: mensaje directo)
                Console.info("GA2-REP", "Esperando solicitud directa...");
                String solicitud = rep.recvStr();
                
                if (solicitud == null || solicitud.isEmpty()) {
                    Console.warn("GA2-REP", "Solicitud vacía");
                    continue;
                }
                Console.info("GA2-REP", "Solicitud: " + solicitud);

                // Procesar solicitud (solo actualiza DB2.txt y Prestamos2.txt, sin sincronizar con GA)
                String respuesta = handleRequest(solicitud);
                Console.info("GA2-REP", "Respuesta: " + respuesta);
                
                // Si la operación fue exitosa, registrar cambio en log y notificar a GA
                if (esOperacionExitosa(respuesta, solicitud)) {
                    String tipoOperacion = extraerTipoOperacion(solicitud);
                    String datos = extraerDatosOperacion(solicitud);
                    registerChange(tipoOperacion, datos);
                    enviarNotificacionAGa(tipoOperacion, datos);
                }
            
                // Enviar respuesta (formato REP: respuesta directa)
                rep.send(respuesta);
                Console.info("GA2-REP", "Respuesta enviada");
                
            } catch (Exception e) {
                Console.error("GA2-REP", "Error: " + e.getMessage());
            }
        }
    }

    //  Función principal de procesamiento
    private String handleRequest(String solicitud) {
        if (solicitud == null || solicitud.isEmpty()) {
            return "Solicitud vacía o nula";
        }
        String respuesta = router.dispatch(solicitud);
        if (UNKNOWN_RESPONSE.equals(respuesta)) {
            System.out.println("GA2:  Solicitud desconocida -> " + solicitud);
        }
        return respuesta;
    }
    
    // Procesa solicitud de sincronización (retorna cambios pendientes)
    private String handleRequestSync() {
        List<String> cambios = obtenerCambiosPendientes();
        if (cambios.isEmpty()) {
            return "SIN_CAMBIOS";
        }
        // Retornar cambios separados por "|" para facilitar parsing
        return String.join("|", cambios);
    }
    
    // Procesa notificación de cambios sincronizados
    private String procesarCambiosSincronizados(String solicitud) {
        try {
            // Formato: CAMBIOS_SINCRONIZADOS:CAMBIO1|CAMBIO2|CAMBIO3
            // Donde cada CAMBIO es el string completo del cambio
            String cambiosStr = solicitud.substring("CAMBIOS_SINCRONIZADOS:".length());
            if (cambiosStr.isEmpty()) {
                return "OK";
            }
            
            String[] cambiosArray = cambiosStr.split("\\|");
            List<String> cambiosParaEliminar = new ArrayList<>();
            
            // Buscar cambios exactos en la lista de pendientes
            for (String cambioRecibido : cambiosArray) {
                cambioRecibido = cambioRecibido.trim();
                for (String cambio : cambiosPendientes) {
                    if (cambio.equals(cambioRecibido)) {
                        cambiosParaEliminar.add(cambio);
                        break;
                    }
                }
            }
            
            marcarCambioSincronizado(cambiosParaEliminar);
            return "OK";
        } catch (Exception e) {
            System.err.println("GA2: Error al procesar cambios sincronizados: " + e.getMessage());
            e.printStackTrace();
            return "ERROR";
        }
    }
    
    // Verifica si una operación fue exitosa
    private boolean esOperacionExitosa(String respuesta, String solicitud) {
        if (solicitud.startsWith("Disponibilidad?")) {
            return respuesta.equals("SI");
        } else if (solicitud.startsWith("DEVOLVER")) {
            return respuesta.equals("Devolución registrada exitosamente");
        } else if (solicitud.startsWith("RENOVAR")) {
            return respuesta.contains("exitoso") || respuesta.contains("nuevo préstamo");
        }
        return false;
    }
    
    // Extrae el tipo de operación de una solicitud
    private String extraerTipoOperacion(String solicitud) {
        if (solicitud.startsWith("Disponibilidad?")) {
            return "PRESTAMO";
        } else if (solicitud.startsWith("DEVOLVER")) {
            return "DEVOLUCION";
        } else if (solicitud.startsWith("RENOVAR")) {
            return "RENOVACION";
        }
        return "DESCONOCIDO";
    }
    
    // Extrae los datos de una operación (ID del libro)
    private String extraerDatosOperacion(String solicitud) {
        String idLibro = "";
        if (solicitud.startsWith("Disponibilidad?")) {
            String[] partes = solicitud.split("\\?");
            if (partes.length == 2) {
                idLibro = partes[1].trim();
            }
        } else if (solicitud.startsWith("DEVOLVER")) {
            // Formatos posibles: "DEVOLVER DEVOLVER:ID" o "DEVOLVER ID"
            String[] partes = solicitud.split(" ");
            for (int i = 1; i < partes.length; i++) {
                String parte = partes[i].trim();
                if (parte.contains(":")) {
                    String[] subPartes = parte.split(":");
                    if (subPartes.length >= 2) {
                        idLibro = subPartes[subPartes.length - 1].trim();
                        break;
                    }
                } else if (!parte.isEmpty() && parte.matches("\\d+")) {
                    idLibro = parte;
                    break;
                }
            }
        } else if (solicitud.startsWith("RENOVAR")) {
            // Formatos posibles: "RENOVAR RENOVAR:ID" o "RENOVAR ID"
            String[] partes = solicitud.split(" ");
            for (int i = 1; i < partes.length; i++) {
                String parte = partes[i].trim();
                if (parte.contains(":")) {
                    String[] subPartes = parte.split(":");
                    if (subPartes.length >= 2) {
                        idLibro = subPartes[subPartes.length - 1].trim();
                        break;
                    }
                } else if (!parte.isEmpty() && parte.matches("\\d+")) {
                    idLibro = parte;
                    break;
                }
            }
        }
        return "ID=" + idLibro;
    }

    //  Función para manejar disponibilidad
    private String handleAvailability(String solicitud) {
        System.out.println("GA2:  Se consultó disponibilidad de libro -> " + solicitud);
        
        // Extraer ID del libro (formato: Disponibilidad?ID)
        String idLibro = null;
        if (solicitud.contains("?")) {
            String[] partes = solicitud.split("\\?", 2);
            if (partes.length == 2) {
                idLibro = partes[1].trim();
            }
        }
        
        if (idLibro == null || idLibro.isEmpty()) {
            return "Error: ID de libro no válido";
        }
        
        // Leer DB2.txt
        List<String> lineas = leerDB();
        if (lineas == null) {
            return "Error: No se pudo leer la base de datos";
        }
        
        // Buscar el libro por ID
        String[] libro = buscarLibro(idLibro, lineas);
        if (libro == null) {
            System.out.println("GA2:  Libro con ID " + idLibro + " no encontrado en DB2.txt");
            return "NO (libro no existe)";
        }
        
        // Verificar estado del libro
        String estado = libro[3].trim();
        System.out.println("GA2:  Libro ID " + idLibro + " encontrado en DB2.txt - Estado actual: '" + estado + "'");
        if (estado.equals("DISPONIBLE")) {
            // Actualizar estado a PRESTADO en DB2.txt
            System.out.println("GA2:  Actualizando estado del libro ID " + idLibro + " en DB2.txt: DISPONIBLE -> PRESTADO");
            boolean actualizado = updateBookStatus(idLibro, "PRESTADO");
            if (actualizado) {
                System.out.println("GA2:  Libro ID " + idLibro + " marcado como PRESTADO en DB2.txt");
                // Registrar el préstamo en Prestamos2.txt
                System.out.println("GA2:  Registrando préstamo del libro ID " + idLibro + " en Prestamos2.txt");
                logLoan(idLibro);
                return "SI";
            } else {
                return "Error: No se pudo actualizar el estado del libro en DB2.txt";
            }
        } else if (estado.equals("PRESTADO")) {
            System.out.println("GA2:  Libro ID " + idLibro + " ya está PRESTADO en DB2.txt");
            return "NO (ya está prestado)";
        } else {
            return "Error: Estado desconocido: " + estado;
        }
    }
    
    // Lee todo el archivo DB2.txt y retorna una lista de líneas
    private List<String> leerDB() {
        try {
            File archivo = new File(BOOK_DB_PATH);
            if (!archivo.exists()) {
                System.err.println("GA2:  ✗ ERROR - Archivo DB2.txt no encontrado en: " + BOOK_DB_PATH);
                return null;
            }
            
            System.out.println("GA2:  Leyendo DB2.txt desde: " + BOOK_DB_PATH);
            List<String> lineas = Files.readAllLines(Paths.get(BOOK_DB_PATH), StandardCharsets.UTF_8);
            System.out.println("GA2:  ✓ Archivo DB2.txt leído correctamente (" + lineas.size() + " líneas)");
            return lineas;
        } catch (IOException e) {
            System.err.println("GA2:  Error al leer DB2.txt: " + e.getMessage());
            return null;
        }
    }
    
    // Busca un libro por ID en las líneas del archivo
    private String[] buscarLibro(String idLibro, List<String> lineas) {
        for (String linea : lineas) {
            if (linea == null || linea.trim().isEmpty()) {
                continue;
            }
            
            String[] libro = parsearLineaLibro(linea);
            if (libro != null && libro[0].trim().equals(idLibro)) {
                return libro;
            }
        }
        return null;
    }
    
    // Parsea una línea del formato "ID, NOMBRE, AUTOR, ESTADO"
    private String[] parsearLineaLibro(String linea) {
        if (linea == null || linea.trim().isEmpty()) {
            return null;
        }
        
        // Dividir por comas, pero preservar espacios
        String[] partes = linea.split(",", 4);
        if (partes.length == 4) {
            return partes;
        }
        return null;
    }
    
    // Actualiza el estado del libro en DB2.txt
    private boolean updateBookStatus(String idLibro, String nuevoEstado) {
        try {
            // Leer todas las líneas
            List<String> lineas = leerDB();
            if (lineas == null) {
                System.err.println("GA2:  Error - No se pudieron leer las líneas de DB2.txt");
                return false;
            }
            
            System.out.println("GA2:  Buscando libro ID: '" + idLibro + "' en DB2.txt (" + lineas.size() + " líneas)");
            
            // Actualizar la línea correspondiente
            List<String> lineasActualizadas = new ArrayList<>();
            boolean encontrado = false;
            
            for (int i = 0; i < lineas.size(); i++) {
                String linea = lineas.get(i);
                
                if (linea == null || linea.trim().isEmpty()) {
                    lineasActualizadas.add(linea);
                    continue;
                }
                
                String[] libro = parsearLineaLibro(linea);
                if (libro != null) {
                    String idLibroEnArchivo = libro[0].trim();
                    System.out.println("GA2:  Línea " + (i+1) + " - ID en archivo: '" + idLibroEnArchivo + "', buscando: '" + idLibro + "'");
                    
                    if (idLibroEnArchivo.equals(idLibro)) {
                        // Reconstruir la línea con el nuevo estado
                        String estadoAnterior = libro[3].trim();
                        String nuevaLinea = libro[0].trim() + ", " + libro[1].trim() + ", " + 
                                           libro[2].trim() + ", " + nuevoEstado;
                        lineasActualizadas.add(nuevaLinea);
                        encontrado = true;
                        System.out.println("GA2:  ✓ Libro encontrado en línea " + (i+1) + " - Estado: '" + estadoAnterior + "' -> '" + nuevoEstado + "'");
                        System.out.println("GA2:  Nueva línea: " + nuevaLinea);
                    } else {
                        lineasActualizadas.add(linea);
                    }
                } else {
                    lineasActualizadas.add(linea);
                }
            }
            
            if (!encontrado) {
                System.err.println("GA2:  ✗ ERROR - No se encontró el libro con ID '" + idLibro + "' en DB2.txt");
                return false;
            }
            
            // Escribir las líneas actualizadas de vuelta al archivo DB2.txt
            System.out.println("GA2:  Escribiendo " + lineasActualizadas.size() + " líneas actualizadas a DB2.txt...");
            System.out.println("GA2:  Ruta del archivo: " + BOOK_DB_PATH);
            Files.write(Paths.get(BOOK_DB_PATH), lineasActualizadas, StandardCharsets.UTF_8);
            System.out.println("GA2:  ✓ DB2.txt actualizado exitosamente - Libro ID " + idLibro + " ahora está " + nuevoEstado);
            
            // Verificar que el archivo se escribió correctamente
            File archivoVerificar = new File(BOOK_DB_PATH);
            if (archivoVerificar.exists()) {
                System.out.println("GA2:  ✓ Archivo DB2.txt existe y fue actualizado");
            } else {
                System.err.println("GA2:  ✗ ERROR - Archivo DB2.txt no existe después de escribir");
            }
            return true;
            
        } catch (IOException e) {
            System.err.println("GA2:  ✗ ERROR al actualizar DB2.txt: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Registra un préstamo en Prestamos2.txt
    private void logLoan(String idLibro) {
        try {
            // Obtener fecha actual
            LocalDate fechaActual = LocalDate.now();
            // Calcular fecha de devolución (14 días después)
            LocalDate fechaDevolucion = fechaActual.plusDays(14);
            
            // Formatear fechas en formato YYYY-MM-DD
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String fechaPrestamoStr = fechaActual.format(formatter);
            String fechaDevolucionStr = fechaDevolucion.format(formatter);
            
            // Crear la línea del préstamo
            String nuevaLinea = idLibro + ", " + fechaPrestamoStr + ", " + fechaDevolucionStr + ", 1";
            
            // Leer el archivo Prestamos2.txt (si existe)
            List<String> lineas = new ArrayList<>();
            File archivoPrestamos = new File(LOANS_PATH);
            if (archivoPrestamos.exists()) {
                lineas = Files.readAllLines(Paths.get(LOANS_PATH), StandardCharsets.UTF_8);
            }
            
            // Agregar la nueva línea
            lineas.add(nuevaLinea);
            
            // Escribir todas las líneas al archivo
            Files.write(Paths.get(LOANS_PATH), lineas, StandardCharsets.UTF_8);
            
            System.out.println("GA2:  Préstamo registrado en Prestamos2.txt: " + nuevaLinea);
            
        } catch (IOException e) {
            System.err.println("GA2:  Error al registrar préstamo en Prestamos2.txt: " + e.getMessage());
        }
    }

    // Extrae el ID del libro de solicitudes como "DEVOLVER:ID", "DEVOLVER DEVOLVER:ID" o "RENOVAR:ID"
    private String extraerIdLibro(String solicitud, String prefijo) {
        if (solicitud == null || solicitud.isEmpty()) {
            return null;
        }
        
        // Normalizar: eliminar prefijos múltiples y espacios
        String normalizada = solicitud.trim();
        
        // Si empieza con el prefijo, eliminarlo
        while (normalizada.startsWith(prefijo)) {
            normalizada = normalizada.substring(prefijo.length()).trim();
        }
        
        // Buscar el ID después de ":" o directamente como número
        if (normalizada.contains(":")) {
            String[] partes = normalizada.split(":", 2);
            if (partes.length >= 2) {
                String id = partes[1].trim();
                if (id.matches("\\d+")) {
                    return id;
                }
            }
        } else if (normalizada.matches("\\d+")) {
            // Si solo hay números, es el ID directamente
            return normalizada;
        }
        
        return null;
    }
    
    // Lee todo el archivo Prestamos2.txt y retorna una lista de líneas
    private List<String> leerPrestamos() {
        try {
            File archivo = new File(LOANS_PATH);
            if (!archivo.exists()) {
                return new ArrayList<>(); // Retornar lista vacía si no existe
            }
            
            List<String> lineas = Files.readAllLines(Paths.get(LOANS_PATH), StandardCharsets.UTF_8);
            return lineas;
        } catch (IOException e) {
            System.err.println("GA2:  Error al leer Prestamos2.txt: " + e.getMessage());
            return null;
        }
    }
    
    // Parsea una línea de Prestamos2.txt: "ID, fechaPrestamo, fechaDevolucion, vecesPrestadas"
    private String[] parsearPrestamo(String linea) {
        if (linea == null || linea.trim().isEmpty()) {
            return null;
        }
        
        String[] partes = linea.split(",");
        if (partes.length >= 4) {
            // Retornar las 4 partes: ID, fechaPrestamo, fechaDevolucion, vecesPrestadas
            return new String[] {
                partes[0].trim(),
                partes[1].trim(),
                partes[2].trim(),
                partes[3].trim()
            };
        }
        return null;
    }
    
    // Busca una línea en Prestamos2.txt por ID del libro
    private String buscarPrestamo(String idLibro, List<String> lineas) {
        if (lineas == null) {
            return null;
        }
        
        for (String linea : lineas) {
            if (linea == null || linea.trim().isEmpty()) {
                continue;
            }
            
            String[] prestamo = parsearPrestamo(linea);
            if (prestamo != null && prestamo[0].equals(idLibro)) {
                return linea;
            }
        }
        return null;
    }
    
    // Elimina todas las líneas relacionadas con un ID de Prestamos2.txt
    private boolean eliminarPrestamo(String idLibro) {
        try {
            List<String> lineas = leerPrestamos();
            if (lineas == null) {
                return false;
            }
            
            List<String> lineasActualizadas = new ArrayList<>();
            boolean encontrado = false;
            
            for (String linea : lineas) {
                if (linea == null || linea.trim().isEmpty()) {
                    continue; // Omitir líneas vacías
                }
                
                String[] prestamo = parsearPrestamo(linea);
                if (prestamo != null && !prestamo[0].equals(idLibro)) {
                    // Solo agregar líneas que NO corresponden al ID a eliminar
                    lineasActualizadas.add(linea);
                } else if (prestamo != null && prestamo[0].equals(idLibro)) {
                    encontrado = true;
                }
            }
            
            if (!encontrado) {
                System.out.println("GA2:  No se encontró préstamo con ID " + idLibro + " en Prestamos2.txt");
                return false;
            }
            
            // Escribir las líneas actualizadas de vuelta al archivo
            Files.write(Paths.get(LOANS_PATH), lineasActualizadas, StandardCharsets.UTF_8);
            System.out.println("GA2:  Préstamo con ID " + idLibro + " eliminado de Prestamos2.txt");
            return true;
            
        } catch (IOException e) {
            System.err.println("GA2:  Error al eliminar préstamo de Prestamos2.txt: " + e.getMessage());
            return false;
        }
    }
    
    // Actualiza una línea específica en Prestamos2.txt
    private boolean actualizarPrestamo(String idLibro, String nuevaFechaDevolucion, String nuevasVecesPrestadas) {
        try {
            List<String> lineas = leerPrestamos();
            if (lineas == null) {
                return false;
            }
            
            List<String> lineasActualizadas = new ArrayList<>();
            boolean encontrado = false;
            
            for (String linea : lineas) {
                if (linea == null || linea.trim().isEmpty()) {
                    continue;
                }
                
                String[] prestamo = parsearPrestamo(linea);
                if (prestamo != null && prestamo[0].equals(idLibro)) {
                    // Actualizar la línea con la nueva fecha de devolución y veces prestadas
                    String nuevaLinea = idLibro + ", " + prestamo[1] + ", " + nuevaFechaDevolucion + ", " + nuevasVecesPrestadas;
                    lineasActualizadas.add(nuevaLinea);
                    encontrado = true;
                } else {
                    lineasActualizadas.add(linea);
                }
            }
            
            if (!encontrado) {
                System.err.println("GA2:  No se encontró préstamo con ID " + idLibro + " para actualizar");
                return false;
            }
            
            // Escribir las líneas actualizadas de vuelta al archivo
            Files.write(Paths.get(LOANS_PATH), lineasActualizadas, StandardCharsets.UTF_8);
            System.out.println("GA2:  Préstamo con ID " + idLibro + " actualizado en Prestamos2.txt");
            return true;
            
        } catch (IOException e) {
            System.err.println("GA2:  Error al actualizar préstamo en Prestamos2.txt: " + e.getMessage());
            return false;
        }
    }

    //  Función para manejar devoluciones
    private String handleReturn(String solicitud) {
        System.out.println("GA2:  Se registró devolución -> " + solicitud);
        
        // Extraer ID del libro (formato: "DEVOLVER:ID" o "DEVOLVER DEVOLVER:ID")
        String idLibro = extraerIdLibro(solicitud, "DEVOLVER");
        if (idLibro == null || idLibro.isEmpty()) {
            return "Error: ID de libro no válido";
        }
        
        // Leer DB2.txt
        List<String> lineas = leerDB();
        if (lineas == null) {
            return "Error: No se pudo leer la base de datos";
        }
        
        // Buscar el libro por ID
        String[] libro = buscarLibro(idLibro, lineas);
        if (libro == null) {
            System.out.println("GA2:  Libro con ID " + idLibro + " no encontrado");
            return "Error: Libro no encontrado";
        }
        
        // Verificar estado del libro
        String estado = libro[3].trim();
        if (estado.equals("PRESTADO")) {
            // Cambiar estado a DISPONIBLE en DB2.txt
            System.out.println("GA2:  Actualizando estado del libro ID " + idLibro + " en DB2.txt: PRESTADO -> DISPONIBLE");
            boolean actualizado = updateBookStatus(idLibro, "DISPONIBLE");
            if (actualizado) {
                System.out.println("GA2:  Libro ID " + idLibro + " marcado como DISPONIBLE en DB2.txt");
                
                // Eliminar todas las líneas relacionadas con ese ID en Prestamos2.txt
                System.out.println("GA2:  Eliminando préstamo del libro ID " + idLibro + " de Prestamos2.txt");
                eliminarPrestamo(idLibro);
                
                return "Devolución registrada exitosamente";
            } else {
                return "Error: No se pudo actualizar el estado del libro en DB2.txt";
            }
        } else {
            System.out.println("GA2:  Libro ID " + idLibro + " no está prestado (estado: " + estado + ")");
            return "Libro no está prestado";
        }
    }

    //  Función para manejar renovaciones
    private String handleRenewal(String solicitud) {
        System.out.println("GA2:  Se registró renovación -> " + solicitud);
        
        // Extraer ID del libro (formato: "RENOVAR:ID" o "RENOVAR RENOVAR:ID")
        String idLibro = extraerIdLibro(solicitud, "RENOVAR");
        if (idLibro == null || idLibro.isEmpty()) {
            return "Error: ID de libro no válido";
        }
        
        // Leer DB2.txt
        List<String> lineas = leerDB();
        if (lineas == null) {
            return "Error: No se pudo leer la base de datos";
        }
        
        // Buscar el libro por ID
        String[] libro = buscarLibro(idLibro, lineas);
        if (libro == null) {
            System.out.println("GA2:  Libro con ID " + idLibro + " no encontrado");
            return "Error: Libro no encontrado";
        }
        
        // Verificar estado del libro
        String estado = libro[3].trim();
        
        if (estado.equals("PRESTADO")) {
            // Libro está prestado: buscar en Prestamos2.txt
            List<String> lineasPrestamos = leerPrestamos();
            if (lineasPrestamos == null) {
                return "Error: No se pudo leer Prestamos2.txt";
            }
            
            String prestamoLinea = buscarPrestamo(idLibro, lineasPrestamos);
            if (prestamoLinea == null) {
                System.out.println("GA2:  No se encontró registro de préstamo para el libro ID " + idLibro + " en Prestamos2.txt");
                return "Error: No se encontró registro de préstamo";
            }
            
            // Parsear el préstamo
            String[] prestamo = parsearPrestamo(prestamoLinea);
            if (prestamo == null || prestamo.length < 4) {
                return "Error: Formato de préstamo inválido";
            }
            
            String vecesPrestadas = prestamo[3].trim();
            
            if (vecesPrestadas.equals("1")) {
                // Primera renovación: actualizar fecha de devolución (una semana después del día actual)
                LocalDate fechaActual = LocalDate.now();
                LocalDate nuevaFechaDevolucion = fechaActual.plusDays(7);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                String nuevaFechaDevolucionStr = nuevaFechaDevolucion.format(formatter);
                
                // Actualizar en Prestamos2.txt: cambiar fecha de devolución y veces prestadas a "2"
                boolean actualizado = actualizarPrestamo(idLibro, nuevaFechaDevolucionStr, "2");
                if (actualizado) {
                    System.out.println("GA2:  Renovación exitosa para libro ID " + idLibro);
                    return "Renovación exitosa, nueva fecha de devolución: " + nuevaFechaDevolucionStr;
                } else {
                    return "Error: No se pudo actualizar el préstamo";
                }
            } else if (vecesPrestadas.equals("2")) {
                // Segunda renovación: no se permiten más renovaciones
                System.out.println("GA2:  Libro ID " + idLibro + " ya tiene 2 renovaciones, no se permiten más");
                return "No se pueden hacer más renovaciones (máximo 2)";
            } else {
                return "Error: Estado de renovación desconocido: " + vecesPrestadas;
            }
            
        } else if (estado.equals("DISPONIBLE")) {
            // Libro está disponible: tratarlo como nuevo préstamo
            // Cambiar estado a PRESTADO en DB2.txt
            System.out.println("GA2:  Actualizando estado del libro ID " + idLibro + " en DB2.txt: DISPONIBLE -> PRESTADO");
            boolean actualizado = updateBookStatus(idLibro, "PRESTADO");
            if (actualizado) {
                System.out.println("GA2:  Libro ID " + idLibro + " marcado como PRESTADO en DB2.txt");
                
                // Registrar el nuevo préstamo en Prestamos2.txt
                System.out.println("GA2:  Registrando nuevo préstamo del libro ID " + idLibro + " en Prestamos2.txt");
                logLoan(idLibro);
                
                // Obtener la fecha de devolución del nuevo préstamo
                LocalDate fechaDevolucion = LocalDate.now().plusDays(14);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                String fechaDevolucionStr = fechaDevolucion.format(formatter);
                
                return "Libro renovado como nuevo préstamo, fecha de devolución: " + fechaDevolucionStr;
            } else {
                return "Error: No se pudo actualizar el estado del libro en DB2.txt";
            }
        } else {
            return "Error: Estado desconocido: " + estado;
        }
    }

}
