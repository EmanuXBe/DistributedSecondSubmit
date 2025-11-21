package org.example.actor;

import org.example.config.Config;
import org.example.util.Console;
import org.example.util.RequestParser;
import org.zeromq.ZMQ;

public class ReturnRenewalActor {

    private static final String GC_HOST = Config.env("GC_HOST", "localhost");
    private static final String GC_PUB_PORT = Config.gcPubPort();
    private static final String GA_HOST = Config.gaHost();
    private static final String GA_PORT = Config.gaPort();
    private static final String GA2_HOST = Config.ga2Host();
    private static final String GA2_PORT = Config.ga2RepPort();

    private static final String SUB_ADDRESS = "tcp://" + GC_HOST + ":" + GC_PUB_PORT;
    private static final String GA_ADDRESS = "tcp://" + GA_HOST + ":" + GA_PORT;
    private static final String GA2_ADDRESS = "tcp://" + GA2_HOST + ":" + GA2_PORT;

    private ZMQ.Context context;
    private ZMQ.Socket subscriber;
    private ZMQ.Socket socketGA;
    private ZMQ.Socket socketGA2;

    public static void main(String[] args) {
        new ReturnRenewalActor().start();
    }

    public void start() {
        context = ZMQ.context(1);
        initSockets();

        Console.info("ACTOR-RR", "Sub a " + SUB_ADDRESS + ", GA=" + GA_ADDRESS + ", GA2=" + GA2_ADDRESS);

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        while (!Thread.currentThread().isInterrupted()) {
            processMessages();
        }

        closeSockets();
    }

    private void initSockets() {
        subscriber = context.socket(ZMQ.SUB);
        subscriber.connect(SUB_ADDRESS);
        subscriber.subscribe("DEVOLUCION".getBytes());
        subscriber.subscribe("RENOVACION".getBytes());

        socketGA = context.socket(ZMQ.REQ);
        socketGA.connect(GA_ADDRESS);
        socketGA.setReceiveTimeOut(3000);

        socketGA2 = context.socket(ZMQ.REQ);
        socketGA2.connect(GA2_ADDRESS);
        System.out.println(" Conectado a GA2 (fallback) en " + GA2_ADDRESS);
    }

    private void processMessages() {
        String mensajeCompleto = subscriber.recvStr();
        Console.info("ACTOR-RR", "Evento GC: " + mensajeCompleto);

        String[] partes = mensajeCompleto.split(" ", 2);
        String topico = partes[0];
        String contenido = partes.length > 1 ? partes[1] : "";

        if (topico.equals("DEVOLUCION")) {
            handleReturn(contenido);
        } else if (topico.equals("RENOVACION")) {
            handleRenewal(contenido);
        } else {
            Console.warn("ACTOR-RR", "Tópico desconocido: " + topico);
        }
    }

    private void handleReturn(String contenido) {
        Console.info("ACTOR-RR", "DEVOLVER -> " + contenido);
        String bookId = RequestParser.extractBookId(contenido);
        if (bookId == null) {
            Console.warn("ACTOR-RR", "ID no reconocido en devolución: " + contenido);
            return;
        }
        String mensaje = "DEVOLVER " + bookId;
        String respGA = sendToGaWithFallback(mensaje);
        System.out.println(" Respuesta recibida: " + respGA);
    }

    private void handleRenewal(String contenido) {
        Console.info("ACTOR-RR", "RENOVAR -> " + contenido);
        String bookId = RequestParser.extractBookId(contenido);
        if (bookId == null) {
            Console.warn("ACTOR-RR", "ID no reconocido en renovación: " + contenido);
            return;
        }
        String mensaje = "RENOVAR " + bookId;
        String respGA = sendToGaWithFallback(mensaje);
        System.out.println(" Respuesta recibida: " + respGA);
    }

    private String sendToGaWithFallback(String mensaje) {
        try {
            socketGA.send(mensaje);
            String respuesta = socketGA.recvStr();
            if (respuesta == null) {
                Console.warn("ACTOR-RR", "GA sin respuesta, fallback GA2");
                return sendToGa2(mensaje);
            }
            Console.info("ACTOR-RR", "Respuesta GA: " + respuesta);
            return respuesta;
        } catch (Exception e) {
            Console.warn("ACTOR-RR", "Error GA: " + e.getMessage() + " -> fallback GA2");
            return sendToGa2(mensaje);
        }
    }

    private String sendToGa2(String mensaje) {
        try {
            Console.info("ACTOR-RR", "GA2 fallback -> " + mensaje);
            socketGA2.send(mensaje);
            String respuesta = socketGA2.recvStr();
            Console.info("ACTOR-RR", "Respuesta GA2: " + respuesta);
            return respuesta;
        } catch (Exception e) {
            Console.error("ACTOR-RR", "GA2 error: " + e.getMessage());
            return "Error: No se pudo comunicar ni con GA ni con GA2";
        }
    }

    private void closeSockets() {
        subscriber.close();
        socketGA.close();
        socketGA2.close();
        context.term();
        Console.info("ACTOR-RR", "Finalizado.");
    }
}
