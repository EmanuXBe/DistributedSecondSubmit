package org.example.storage;

import org.example.config.Config;
import org.example.util.CommandRouter;
import org.example.util.Console;
import org.zeromq.ZMQ;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class StorageReplica {

    private static final String UNKNOWN_RESPONSE = "Solicitud no reconocida";
    private static final String GA_BIND_HOST = Config.env("GA_BIND_HOST", "*");
    private static final String GA_PORT = Config.gaPort();
    private static final String GA2_HOST = Config.ga2Host();
    private static final String GA2_PORT = Config.ga2RouterPort();
    private static final String BOOK_DB_PATH = Config.replicaBookDbPath();
    private static final String LOANS_PATH = Config.replicaLoansPath();
    private static final String PENDING_LOG_PATH = Config.replicaPendingLogPath();
    
    private ZMQ.Context context;
    private ZMQ.Socket responder;
    private ZMQ.Socket dealer;  // Socket DEALER para comunicarse con GA2
    private final CommandRouter localRouter = new CommandRouter(UNKNOWN_RESPONSE)
        .onPrefix("Disponibilidad?", this::handleAvailability)
        .onPrefix("DEVOLVER", this::handleReturn)
        .onPrefix("RENOVAR", this::handleRenewal);

    public static void main(String[] args) {
        new StorageReplica().iniciar();
    }

    public void iniciar() {
        context = ZMQ.context(1);
        responder = context.socket(ZMQ.REP);
        responder.bind("tcp://" + GA_BIND_HOST + ":" + GA_PORT);
        Console.info("GA", "REP en tcp://" + GA_BIND_HOST + ":" + GA_PORT);

        // Inicializar socket DEALER para conectarse a GA2
        inicializarDealerSocket();
        
        // Esperar un momento para que la conexión se establezca
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println(" GA escuchando en tcp://" + GA_BIND_HOST + ":" + GA_PORT + "...");
        System.out.println(" GA conectado a GA2 en " + GA2_HOST + ":" + GA2_PORT + " (DEALER)...");
        
        // Sincronizar con GA2 al iniciar (obtener cambios pendientes)
        sincronizarConGA2();
        enviarCambiosPendientesAGa2();
        
        // Iniciar sincronización periódica cada 30 segundos
        Thread threadSincronizacion = new Thread(() -> sincronizacionPeriodica());
        threadSincronizacion.setDaemon(true);
        threadSincronizacion.start();

        while (!Thread.currentThread().isInterrupted()) {
            String solicitud = responder.recvStr();

            if ("PING".equalsIgnoreCase(solicitud)) {
                responder.send("PONG");
                continue;
            }
            
            // Manejar notificaciones de cambios de GA2
            if (solicitud != null && solicitud.startsWith("NOTIFICACION_CAMBIO:")) {
                manejarNotificacionCambio(solicitud);
                responder.send("OK", 0);
                continue;
            }

            String respuesta = handleRequest(solicitud);
            responder.send(respuesta, 0);
        }

        responder.close();
        dealer.close();
        context.term();
    }
    
    // Inicializa el socket DEALER y lo conecta a GA2
    private void inicializarDealerSocket() {
        dealer = context.socket(ZMQ.DEALER);
        // Establecer una identidad única para este DEALER (importante para que ROUTER pueda responder)
        dealer.setIdentity("GA-DEALER".getBytes());
        // Configurar timeout de recepción (10 segundos)
        dealer.setReceiveTimeOut(10000);
        dealer.connect("tcp://" + GA2_HOST + ":" + GA2_PORT);
        System.out.println(" Socket DEALER conectado a GA2 en " + GA2_HOST + ":" + GA2_PORT);
        System.out.println(" GA:  Identidad del DEALER establecida: GA-DEALER");
        // Pequeño delay para asegurar conexión
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // Envía solicitud a GA2 y espera respuesta
    private String enviarAGa2(String solicitud) {
        try {
            // Enviar solicitud a GA2 usando DEALER socket
            System.out.println("GA:  Enviando mensaje a GA2: " + solicitud);
            boolean enviado = dealer.send(solicitud, 0);
            System.out.println("GA:  Mensaje enviado (" + (enviado ? "exitoso" : "fallido") + "), esperando respuesta...");
            
            // Pequeño delay para asegurar que el mensaje se envíe correctamente
            Thread.sleep(50);
            
            // Esperar respuesta de GA2
            // En jeromq, el DEALER recibe directamente el mensaje del ROUTER
            // ROUTER envía: [identidad] [respuesta], pero el DEALER solo ve la respuesta
            System.out.println("GA:  Esperando respuesta de GA2 (timeout: 10 segundos)...");
            
            // El DEALER recibe directamente el mensaje (jeromq filtra automáticamente la identidad)
            // recvStr() retorna null si hay timeout o error
            String respuesta = dealer.recvStr();
            
            if (respuesta == null) {
                System.err.println("GA:  ✗ ERROR - No se recibió respuesta de GA2 (timeout o error)");
                return "Error: No se recibió respuesta de GA2";
            }
            
            System.out.println("GA:  ✓ Respuesta recibida de GA2: " + respuesta);
            return respuesta;
            
        } catch (Exception e) {
            System.err.println("GA:  ✗ ERROR al comunicarse con GA2: " + e.getMessage());
            e.printStackTrace();
            return "Error: No se pudo comunicar con GA2";
        }
    }

    //  Función principal de procesamiento
    private String handleRequest(String solicitud) {
        if (solicitud == null || solicitud.isEmpty()) {
            return "Solicitud vacía o nula";
        }

        // Primero enviar solicitud a GA2 (base principal)
        System.out.println("GA:  Enviando solicitud a GA2: " + solicitud);
        String respuestaGA2 = enviarAGa2(solicitud);
        boolean ga2Disponible = respuestaGA2 != null && !respuestaGA2.startsWith("Error");

        if (!ga2Disponible) {
            System.out.println("GA:  GA2 no respondió (modo degradado). Procesando localmente y registrando pendiente.");
            String respuestaLocal = handleRequestLocal(solicitud);
            if (respuestaLocal != null && !respuestaLocal.startsWith("Error") && !respuestaLocal.startsWith("Solicitud no reconocida")) {
                appendPendingChange(solicitud);
            }
            return respuestaLocal != null ? respuestaLocal : "Error: No se pudo procesar localmente";
        }

        // Si GA2 fue exitoso, sincronizar procesando localmente
        sincronizarLocalConGA2(solicitud, respuestaGA2);

        // Siempre retornar la respuesta de GA2 (base principal)
        return respuestaGA2;
    }

    // Sincroniza copia local solo cuando GA2 aprobó la operación
    private void sincronizarLocalConGA2(String solicitud, String respuestaGA2) {
        if (solicitud.startsWith("Disponibilidad?")) {
            if (respuestaGA2.equals("SI")) {
                System.out.println("GA:  GA2 procesó exitosamente, sincronizando localmente (préstamo)...");
                String resultado = handleAvailability(solicitud); // Procesar localmente (solo para sincronización)
                System.out.println("GA:  Resultado de sincronización local: " + resultado);
            } else {
                System.out.println("GA:  GA2 retornó '" + respuestaGA2 + "', NO procesando localmente");
            }
        } else if (solicitud.startsWith("DEVOLVER")) {
            if (respuestaGA2.equals("Devolución registrada exitosamente")) {
                System.out.println("GA:  GA2 procesó exitosamente, sincronizando localmente (devolución)...");
                handleReturn(solicitud); // Procesar localmente (solo para sincronización)
            } else {
                System.out.println("GA:  GA2 retornó '" + respuestaGA2 + "', NO procesando localmente");
            }
        } else if (solicitud.startsWith("RENOVAR")) {
            if (respuestaGA2.contains("exitoso") || respuestaGA2.contains("nuevo préstamo")) {
                if (!respuestaGA2.contains("No se pueden hacer más renovaciones") && !respuestaGA2.startsWith("Error")) {
                    System.out.println("GA:  GA2 procesó exitosamente, sincronizando localmente (renovación)...");
                    handleRenewal(solicitud); // Procesar localmente (solo para sincronización)
                } else {
                    System.out.println("GA:  GA2 retornó '" + respuestaGA2 + "', NO procesando localmente");
                }
            } else {
                System.out.println("GA:  GA2 retornó '" + respuestaGA2 + "', NO procesando localmente");
            }
        }
    }

    // Procesamiento directo en la réplica cuando GA2 no está disponible
    private String handleRequestLocal(String solicitud) {
        if (solicitud == null || solicitud.isEmpty()) {
            return "Solicitud vacía o nula";
        }
        String respuesta = localRouter.dispatch(solicitud);
        if (UNKNOWN_RESPONSE.equals(respuesta)) {
            System.out.println("GA:  Solicitud no reconocida localmente: " + solicitud);
        }
        return respuesta;
    }

    //  Función para manejar disponibilidad
    private String handleAvailability(String solicitud) {
        System.out.println("GA:  Se consultó disponibilidad de libro -> " + solicitud);
        
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
        
        // Leer DB.txt
        List<String> lineas = leerDB();
        if (lineas == null) {
            return "Error: No se pudo leer la base de datos";
        }
        
        // Buscar el libro por ID
        String[] libro = buscarLibro(idLibro, lineas);
        if (libro == null) {
            System.out.println("GA:  Libro con ID " + idLibro + " no encontrado");
            return "NO (libro no existe)";
        }
        
        // Verificar estado del libro
        String estado = libro[3].trim();
        System.out.println("GA:  Estado del libro ID " + idLibro + " en DB.txt: '" + estado + "'");
        if (estado.equals("DISPONIBLE")) {
            // Actualizar estado a PRESTADO
            System.out.println("GA:  Actualizando estado del libro ID " + idLibro + " de DISPONIBLE a PRESTADO en DB.txt");
            boolean actualizado = updateBookStatus(idLibro, "PRESTADO");
            if (actualizado) {
                System.out.println("GA:  ✓ Libro ID " + idLibro + " marcado como PRESTADO en DB.txt");
                // Registrar el préstamo en Prestamos.txt
                System.out.println("GA:  Llamando a logLoan() para libro ID: " + idLibro);
                logLoan(idLibro);
                return "SI";
            } else {
                System.err.println("GA:  ✗ Error: No se pudo actualizar el estado del libro en DB.txt");
                return "Error: No se pudo actualizar el estado del libro";
            }
        } else if (estado.equals("PRESTADO")) {
            System.out.println("GA:  Libro ID " + idLibro + " ya está PRESTADO");
            return "NO (ya está prestado)";
        } else {
            return "Error: Estado desconocido: " + estado;
        }
    }
    
    // Lee todo el archivo DB.txt y retorna una lista de líneas
    private List<String> leerDB() {
        try {
            File archivo = new File(BOOK_DB_PATH);
            if (!archivo.exists()) {
                System.err.println("GA:  Error - Archivo DB.txt no encontrado en: " + BOOK_DB_PATH);
                return null;
            }
            
            List<String> lineas = Files.readAllLines(Paths.get(BOOK_DB_PATH), StandardCharsets.UTF_8);
            return lineas;
        } catch (IOException e) {
            System.err.println("GA:  Error al leer DB.txt: " + e.getMessage());
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
    
    // Actualiza el estado del libro en DB.txt
    private boolean updateBookStatus(String idLibro, String nuevoEstado) {
        try {
            // Leer todas las líneas
            List<String> lineas = leerDB();
            if (lineas == null) {
                return false;
            }
            
            // Actualizar la línea correspondiente
            List<String> lineasActualizadas = new ArrayList<>();
            boolean encontrado = false;
            
            for (String linea : lineas) {
                if (linea == null || linea.trim().isEmpty()) {
                    lineasActualizadas.add(linea);
                    continue;
                }
                
                String[] libro = parsearLineaLibro(linea);
                if (libro != null && libro[0].trim().equals(idLibro)) {
                    // Reconstruir la línea con el nuevo estado
                    String nuevaLinea = libro[0].trim() + ", " + libro[1].trim() + ", " + 
                                       libro[2].trim() + ", " + nuevoEstado;
                    lineasActualizadas.add(nuevaLinea);
                    encontrado = true;
                } else {
                    lineasActualizadas.add(linea);
                }
            }
            
            if (!encontrado) {
                System.err.println("GA:  No se encontró el libro para actualizar");
                return false;
            }
            
            // Escribir las líneas actualizadas de vuelta al archivo
            Files.write(Paths.get(BOOK_DB_PATH), lineasActualizadas, StandardCharsets.UTF_8);
            return true;
            
        } catch (IOException e) {
            System.err.println("GA:  Error al actualizar DB.txt: " + e.getMessage());
            return false;
        }
    }

    // Registra un préstamo en Prestamos.txt
    private void logLoan(String idLibro) {
        try {
            System.out.println("GA:  === INICIANDO registro de préstamo para libro ID: " + idLibro + " ===");
            
            // Obtener fecha actual
            LocalDate fechaActual = LocalDate.now();
            // Calcular fecha de devolución (14 días después)
            LocalDate fechaDevolucion = fechaActual.plusDays(14);
            
            // Formatear fechas en formato YYYY-MM-DD
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String fechaPrestamoStr = fechaActual.format(formatter);
            String fechaDevolucionStr = fechaDevolucion.format(formatter);
            
            System.out.println("GA:  Fecha préstamo: " + fechaPrestamoStr + ", Fecha devolución: " + fechaDevolucionStr);
            
            // Crear la línea del préstamo: ID, fecha actual, fecha devolución, 1
            String nuevaLinea = idLibro + ", " + fechaPrestamoStr + ", " + fechaDevolucionStr + ", 1";
            System.out.println("GA:  Nueva línea a agregar: " + nuevaLinea);
            
            // Leer el archivo Prestamos.txt (si existe)
            List<String> lineas = new ArrayList<>();
            File archivoPrestamos = new File(LOANS_PATH);
            System.out.println("GA:  Ruta de Prestamos.txt: " + LOANS_PATH);
            System.out.println("GA:  Archivo existe: " + archivoPrestamos.exists());
            
            if (archivoPrestamos.exists()) {
                lineas = Files.readAllLines(Paths.get(LOANS_PATH), StandardCharsets.UTF_8);
                System.out.println("GA:  Archivo Prestamos.txt leído correctamente (" + lineas.size() + " líneas existentes)");
            } else {
                System.out.println("GA:  Archivo Prestamos.txt no existe, se creará uno nuevo");
            }
            
            // Agregar la nueva línea
            lineas.add(nuevaLinea);
            System.out.println("GA:  Total de líneas después de agregar: " + lineas.size());
            
            // Escribir todas las líneas al archivo
            Files.write(Paths.get(LOANS_PATH), lineas, StandardCharsets.UTF_8);
            System.out.println("GA:  ✓✓✓ Préstamo registrado exitosamente en Prestamos.txt: " + nuevaLinea);
            System.out.println("GA:  === FIN registro de préstamo ===");
            
        } catch (IOException e) {
            System.err.println("GA:  ✗✗✗ ERROR al registrar préstamo en Prestamos.txt: " + e.getMessage());
            e.printStackTrace();
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
    
    // Lee todo el archivo Prestamos.txt y retorna una lista de líneas
    private List<String> leerPrestamos() {
        try {
            File archivo = new File(LOANS_PATH);
            if (!archivo.exists()) {
                return new ArrayList<>(); // Retornar lista vacía si no existe
            }
            
            List<String> lineas = Files.readAllLines(Paths.get(LOANS_PATH), StandardCharsets.UTF_8);
            return lineas;
        } catch (IOException e) {
            System.err.println("GA:  Error al leer Prestamos.txt: " + e.getMessage());
            return null;
        }
    }
    
    // Parsea una línea de Prestamos.txt: "ID, fechaPrestamo, fechaDevolucion, vecesPrestadas"
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
    
    // Busca una línea en Prestamos.txt por ID del libro
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
    
    // Elimina todas las líneas relacionadas con un ID de Prestamos.txt
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
                System.out.println("GA:  No se encontró préstamo con ID " + idLibro + " en Prestamos.txt");
                return false;
            }
            
            // Escribir las líneas actualizadas de vuelta al archivo
            Files.write(Paths.get(LOANS_PATH), lineasActualizadas, StandardCharsets.UTF_8);
            System.out.println("GA:  Préstamo con ID " + idLibro + " eliminado de Prestamos.txt");
            return true;
            
        } catch (IOException e) {
            System.err.println("GA:  Error al eliminar préstamo de Prestamos.txt: " + e.getMessage());
            return false;
        }
    }
    
    // Actualiza una línea específica en Prestamos.txt
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
                System.err.println("GA:  No se encontró préstamo con ID " + idLibro + " para actualizar");
                return false;
            }
            
            // Escribir las líneas actualizadas de vuelta al archivo
            Files.write(Paths.get(LOANS_PATH), lineasActualizadas, StandardCharsets.UTF_8);
            System.out.println("GA:  Préstamo con ID " + idLibro + " actualizado en Prestamos.txt");
            return true;
            
        } catch (IOException e) {
            System.err.println("GA:  Error al actualizar préstamo en Prestamos.txt: " + e.getMessage());
            return false;
        }
    }

    //  Función para manejar devoluciones
    private String handleReturn(String solicitud) {
        System.out.println("GA:  Se registró devolución -> " + solicitud);
        
        // Extraer ID del libro (formato: "DEVOLVER:ID" o "DEVOLVER DEVOLVER:ID")
        String idLibro = extraerIdLibro(solicitud, "DEVOLVER");
        if (idLibro == null || idLibro.isEmpty()) {
            return "Error: ID de libro no válido";
        }
        
        // Leer DB.txt
        List<String> lineas = leerDB();
        if (lineas == null) {
            return "Error: No se pudo leer la base de datos";
        }
        
        // Buscar el libro por ID
        String[] libro = buscarLibro(idLibro, lineas);
        if (libro == null) {
            System.out.println("GA:  Libro con ID " + idLibro + " no encontrado");
            return "Error: Libro no encontrado";
        }
        
        // Verificar estado del libro
        String estado = libro[3].trim();
        if (estado.equals("PRESTADO")) {
            // Cambiar estado a DISPONIBLE
            boolean actualizado = updateBookStatus(idLibro, "DISPONIBLE");
            if (actualizado) {
                System.out.println("GA:  Libro ID " + idLibro + " marcado como DISPONIBLE");
                
                // Eliminar todas las líneas relacionadas con ese ID en Prestamos.txt
                eliminarPrestamo(idLibro);
                
                return "Devolución registrada exitosamente";
            } else {
                return "Error: No se pudo actualizar el estado del libro";
            }
        } else {
            System.out.println("GA:  Libro ID " + idLibro + " no está prestado (estado: " + estado + ")");
            return "Libro no está prestado";
        }
    }

    //  Función para manejar renovaciones
    private String handleRenewal(String solicitud) {
        System.out.println("GA:  Se registró renovación -> " + solicitud);
        
        // Extraer ID del libro (formato: "RENOVAR:ID" o "RENOVAR RENOVAR:ID")
        String idLibro = extraerIdLibro(solicitud, "RENOVAR");
        if (idLibro == null || idLibro.isEmpty()) {
            return "Error: ID de libro no válido";
        }
        
        // Leer DB.txt
        List<String> lineas = leerDB();
        if (lineas == null) {
            return "Error: No se pudo leer la base de datos";
        }
        
        // Buscar el libro por ID
        String[] libro = buscarLibro(idLibro, lineas);
        if (libro == null) {
            System.out.println("GA:  Libro con ID " + idLibro + " no encontrado");
            return "Error: Libro no encontrado";
        }
        
        // Verificar estado del libro
        String estado = libro[3].trim();
        
        if (estado.equals("PRESTADO")) {
            // Libro está prestado: buscar en Prestamos.txt
            List<String> lineasPrestamos = leerPrestamos();
            if (lineasPrestamos == null) {
                return "Error: No se pudo leer Prestamos.txt";
            }
            
            String prestamoLinea = buscarPrestamo(idLibro, lineasPrestamos);
            if (prestamoLinea == null) {
                System.out.println("GA:  No se encontró registro de préstamo para el libro ID " + idLibro);
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
                
                // Actualizar en Prestamos.txt: cambiar fecha de devolución y veces prestadas a "2"
                boolean actualizado = actualizarPrestamo(idLibro, nuevaFechaDevolucionStr, "2");
                if (actualizado) {
                    System.out.println("GA:  Renovación exitosa para libro ID " + idLibro);
                    return "Renovación exitosa, nueva fecha de devolución: " + nuevaFechaDevolucionStr;
                } else {
                    return "Error: No se pudo actualizar el préstamo";
                }
            } else if (vecesPrestadas.equals("2")) {
                // Segunda renovación: no se permiten más renovaciones
                System.out.println("GA:  Libro ID " + idLibro + " ya tiene 2 renovaciones, no se permiten más");
                return "No se pueden hacer más renovaciones (máximo 2)";
            } else {
                return "Error: Estado de renovación desconocido: " + vecesPrestadas;
            }
            
        } else if (estado.equals("DISPONIBLE")) {
            // Libro está disponible: tratarlo como nuevo préstamo
            // Cambiar estado a PRESTADO
            boolean actualizado = updateBookStatus(idLibro, "PRESTADO");
            if (actualizado) {
                System.out.println("GA:  Libro ID " + idLibro + " renovado como nuevo préstamo");
                
                // Registrar el nuevo préstamo en Prestamos.txt
                logLoan(idLibro);
                
                // Obtener la fecha de devolución del nuevo préstamo
                LocalDate fechaDevolucion = LocalDate.now().plusDays(14);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                String fechaDevolucionStr = fechaDevolucion.format(formatter);
                
                return "Libro renovado como nuevo préstamo, fecha de devolución: " + fechaDevolucionStr;
            } else {
                return "Error: No se pudo actualizar el estado del libro";
            }
        } else {
            return "Error: Estado desconocido: " + estado;
        }
    }
    
    // Sincroniza con GA2 al iniciar (obtiene cambios pendientes)
    private void sincronizarConGA2() {
        try {
            System.out.println("GA:  Iniciando sincronización con GA2...");
            String respuesta = enviarAGa2("OBTENER_CAMBIOS");
            
            if (respuesta == null || respuesta.equals("Error:") || respuesta.startsWith("Error")) {
                System.out.println("GA:  No se pudieron obtener cambios pendientes de GA2");
                return;
            }
            
            if (respuesta.equals("SIN_CAMBIOS")) {
                System.out.println("GA:  No hay cambios pendientes en GA2");
                return;
            }
            
            // Parsear cambios (separados por "|")
            String[] cambios = respuesta.split("\\|");
            System.out.println("GA:  Obtenidos " + cambios.length + " cambios pendientes de GA2");
            
            List<String> cambiosAplicados = new ArrayList<>();
            for (String cambio : cambios) {
                if (cambio != null && !cambio.trim().isEmpty()) {
                    boolean aplicado = aplicarCambioDesdeLog(cambio);
                    if (aplicado) {
                        cambiosAplicados.add(cambio);
                    }
                }
            }
            
            // Notificar a GA2 qué cambios fueron sincronizados
            if (!cambiosAplicados.isEmpty()) {
                // Enviar cambios completos separados por "|"
                String cambiosStr = String.join("|", cambiosAplicados);
                enviarAGa2("CAMBIOS_SINCRONIZADOS:" + cambiosStr);
                System.out.println("GA:  " + cambiosAplicados.size() + " cambios sincronizados exitosamente");
            }
            
        } catch (Exception e) {
            System.err.println("GA:  Error al sincronizar con GA2: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Envía a GA2 los cambios procesados localmente en modo degradado
    private void enviarCambiosPendientesAGa2() {
        try {
            List<String> pendientes = leerCambiosPendientesLocal();
            if (pendientes.isEmpty()) {
                return;
            }

            System.out.println("GA:  Reintentando " + pendientes.size() + " cambios pendientes hacia GA2...");
            List<String> procesados = new ArrayList<>();
            Set<String> vistos = new HashSet<>();

            for (String entrada : pendientes) {
                if (entrada == null || entrada.trim().isEmpty()) continue;
                String[] parts = entrada.split("\\|", 2);
                String opId = parts.length == 2 ? parts[0] : "";
                String payload = parts.length == 2 ? parts[1] : entrada;

                if (!opId.isEmpty() && !vistos.add(opId)) {
                    continue; // evitar duplicados
                }

                String respuesta = enviarAGa2(payload);
                boolean ok = respuesta != null && !respuesta.startsWith("Error");
                if (ok) {
                    System.out.println("GA:  ✓ Cambio pendiente aplicado en GA2: " + payload);
                    procesados.add(entrada);
                    sincronizarLocalConGA2(payload, respuesta);
                } else {
                    System.out.println("GA:  GA2 aún no acepta el cambio pendiente: " + payload + " -> " + respuesta);
                }
            }

            if (!procesados.isEmpty()) {
                limpiarCambiosPendientesLocal(procesados);
                System.out.println("GA:  Cambios pendientes depurados: " + procesados.size());
            }
        } catch (Exception e) {
            System.err.println("GA:  Error al reenviar cambios pendientes a GA2: " + e.getMessage());
        }
    }
    
    // Sincronización periódica cada 30 segundos
    private void sincronizacionPeriodica() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(30000); // 30 segundos
                System.out.println("GA:  Ejecutando sincronización periódica...");
        sincronizarConGA2();
        enviarCambiosPendientesAGa2();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("GA:  Error en sincronización periódica: " + e.getMessage());
            }
        }
    }

    private void appendPendingChange(String solicitud) {
        try {
            String entry = UUID.randomUUID() + "|" + solicitud;
            Files.write(
                Paths.get(PENDING_LOG_PATH),
                List.of(entry),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            System.out.println("GA:  Cambio pendiente registrado localmente para reenviar: " + entry);
        } catch (IOException e) {
            System.err.println("GA:  Error al registrar cambio pendiente local: " + e.getMessage());
        }
    }

    private List<String> leerCambiosPendientesLocal() {
        try {
            File archivo = new File(PENDING_LOG_PATH);
            if (!archivo.exists()) {
                return new ArrayList<>();
            }
            return Files.readAllLines(Paths.get(PENDING_LOG_PATH), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("GA:  Error al leer cambios pendientes locales: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void limpiarCambiosPendientesLocal(List<String> procesados) {
        try {
            List<String> actuales = leerCambiosPendientesLocal();
            if (actuales.isEmpty()) {
                return;
            }
            List<String> restantes = new ArrayList<>();
            for (String linea : actuales) {
                if (!procesados.contains(linea)) {
                    restantes.add(linea);
                }
            }
            Files.write(Paths.get(PENDING_LOG_PATH), restantes, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("GA:  Error al limpiar cambios pendientes locales: " + e.getMessage());
        }
    }
    
    // Aplica un cambio del log localmente
    private boolean aplicarCambioDesdeLog(String cambio) {
        try {
            // Formato: TIMESTAMP, TIPO, DATOS
            // Ejemplo: "2025-11-19 10:30:00, PRESTAMO, ID=20"
            String[] partes = cambio.split(",", 3);
            if (partes.length < 3) {
                System.err.println("GA:  Formato de cambio inválido: " + cambio);
                return false;
            }
            
            String tipo = partes[1].trim();
            String datos = partes[2].trim();
            
            // Extraer ID del libro
            String idLibro = null;
            if (datos.startsWith("ID=")) {
                idLibro = datos.substring(3).trim();
            }
            
            if (idLibro == null || idLibro.isEmpty()) {
                System.err.println("GA:  No se pudo extraer ID del libro del cambio: " + cambio);
                return false;
            }
            
            System.out.println("GA:  Aplicando cambio desde log: " + tipo + " - ID=" + idLibro);
            
            // Aplicar cambio según el tipo
            if (tipo.equals("PRESTAMO")) {
                // Simular préstamo: actualizar estado a PRESTADO y agregar a Prestamos.txt
                boolean actualizado = updateBookStatus(idLibro, "PRESTADO");
                if (actualizado) {
                    logLoan(idLibro);
                    System.out.println("GA:  ✓ Cambio aplicado: PRESTAMO para libro ID " + idLibro);
                    return true;
                }
            } else if (tipo.equals("DEVOLUCION")) {
                // Simular devolución: actualizar estado a DISPONIBLE y eliminar de Prestamos.txt
                boolean actualizado = updateBookStatus(idLibro, "DISPONIBLE");
                if (actualizado) {
                    eliminarPrestamo(idLibro);
                    System.out.println("GA:  ✓ Cambio aplicado: DEVOLUCION para libro ID " + idLibro);
                    return true;
                }
            } else if (tipo.equals("RENOVACION")) {
                // Simular renovación: actualizar fecha de devolución en Prestamos.txt
                List<String> lineasPrestamos = leerPrestamos();
                if (lineasPrestamos != null) {
                    String prestamoLinea = buscarPrestamo(idLibro, lineasPrestamos);
                    if (prestamoLinea != null) {
                        String[] prestamo = parsearPrestamo(prestamoLinea);
                        if (prestamo != null && prestamo.length >= 4) {
                            String vecesPrestadas = prestamo[3].trim();
                            if (vecesPrestadas.equals("1")) {
                                // Primera renovación: actualizar fecha de devolución
                                LocalDate fechaActual = LocalDate.now();
                                LocalDate nuevaFechaDevolucion = fechaActual.plusDays(7);
                                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                                String nuevaFechaDevolucionStr = nuevaFechaDevolucion.format(formatter);
                                actualizarPrestamo(idLibro, nuevaFechaDevolucionStr, "2");
                                System.out.println("GA:  ✓ Cambio aplicado: RENOVACION para libro ID " + idLibro);
                                return true;
                            }
                        }
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            System.err.println("GA:  Error al aplicar cambio desde log: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    // Maneja notificación de cambio de GA2 (sincronización en tiempo real)
    private void manejarNotificacionCambio(String notificacion) {
        try {
            // Formato: NOTIFICACION_CAMBIO:TIPO:DATOS
            // Ejemplo: "NOTIFICACION_CAMBIO:PRESTAMO:ID=20"
            String[] partes = notificacion.split(":", 3);
            if (partes.length < 3) {
                System.err.println("GA:  Formato de notificación inválido: " + notificacion);
                return;
            }
            
            String tipo = partes[1].trim();
            String datos = partes[2].trim();
            
            System.out.println("GA:  Notificación de cambio recibida: " + tipo + " - " + datos);
            
            // Crear entrada de log para aplicar cambio
            LocalDateTime ahora = LocalDateTime.now();
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String timestamp = ahora.format(formatter);
            String cambio = timestamp + ", " + tipo + ", " + datos;
            
            // Aplicar cambio
            boolean aplicado = aplicarCambioDesdeLog(cambio);
            if (aplicado) {
                System.out.println("GA:  ✓ Cambio aplicado desde notificación");
            } else {
                System.err.println("GA:  ✗ Error al aplicar cambio desde notificación");
            }
            
        } catch (Exception e) {
            System.err.println("GA:  Error al manejar notificación de cambio: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
