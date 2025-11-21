package org.example.front;

import org.example.config.Config;
import org.example.util.Console;
import org.zeromq.ZMQ;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

public class RequestProducer {

    private static final String GC_HOST = Config.env("GC_HOST", "localhost");
    private static final String GC_PORT = Config.gcPsPort();
    private static final String GC_ADDRESS = "tcp://" + GC_HOST + ":" + GC_PORT;
    private static final String REQUESTS_FILE = Config.requestsFilePath();

    private ZMQ.Context context;
    private ZMQ.Socket socketGC;
    private Scanner scanner;

    public static void main(String[] args) {
        new RequestProducer().start();
    }

    public void start() {
        initConnection();
        scanner = new Scanner(System.in);

        Console.info("CLIENTE", "Conectado a " + GC_ADDRESS);
        System.out.println("1) Leer solicitudes desde archivo (" + REQUESTS_FILE + ")");
        System.out.println("2) Ingresar solicitudes manualmente");
        System.out.print("Seleccione una opción (1 o 2): ");
        String opcion = scanner.nextLine();

        try {
            if (opcion.equals("1")) {
                readFromFile();
            } else if (opcion.equals("2")) {
                manualInput();
            } else {
                Console.warn("CLIENTE", "Opción no válida. Fin.");
            }
        } finally {
            closeConnection();
        }
    }

    private void initConnection() {
        context = ZMQ.context(1);
        socketGC = context.socket(ZMQ.REQ);
        socketGC.connect(GC_ADDRESS);
        Console.info("CLIENTE", "Gestor de Carga en " + GC_ADDRESS);
    }

    private void readFromFile() {
        try (BufferedReader br = new BufferedReader(new FileReader(REQUESTS_FILE))) {
            Console.info("CLIENTE", "Leyendo solicitudes desde " + REQUESTS_FILE);

            String linea;
            while ((linea = br.readLine()) != null) {
                processLine(linea);
                Thread.sleep(500); // pausa entre solicitudes
            }

            Console.info("CLIENTE", "Lectura desde archivo finalizada.");

        } catch (IOException e) {
            Console.error("CLIENTE", "Error al leer archivo: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void manualInput() {
        System.out.println("\nIngrese solicitudes (formato: <TIPO> <ID>)");
        System.out.println("Ejemplo: PRESTAMO 1");
        System.out.println("Escriba 'SALIR' para terminar.\n");

        while (true) {
            System.out.print("Ingrese solicitud: ");
            String linea = scanner.nextLine();

            if (linea.equalsIgnoreCase("SALIR")) break;

            processLine(linea);
        }
    }

    private void processLine(String linea) {
        linea = linea.trim();
        if (linea.isEmpty()) return;

        String[] partes = linea.split("[ ,:]+"); // acepta espacio, coma o dos puntos
        if (partes.length < 2) {
            Console.warn("CLIENTE", "Formato inválido: " + linea);
            return;
        }

        String tipo = partes[0].toUpperCase();
        String isbn = partes[1];
        String mensaje = tipo + ":" + isbn;

        sendRequest(mensaje);
    }

    private void sendRequest(String mensaje) {
        Console.info("CLIENTE", "Enviando -> " + mensaje);
        socketGC.send(mensaje, 0);

        String respuesta = socketGC.recvStr();
        Console.info("CLIENTE", "Respuesta GC: " + respuesta);
    }

    private void closeConnection() {
        socketGC.close();
        context.term();
        if (scanner != null) scanner.close();
        Console.info("CLIENTE", "Finalizado.");
    }
}
