package org.example.actor;

import org.example.config.Config;
import org.example.util.Console;
import org.example.util.RequestParser;
import org.zeromq.ZMQ;
import java.util.Objects;

public class LoanActor {

    private static final String ACTOR_BIND_HOST = Config.env("ACTOR_BIND_HOST", "*");
    private static final String ACTOR_PORT = Config.actorPort();
    private static final String GA_HOST = Config.gaHost();
    private static final String GA_PORT = Config.gaPort();
    private static final String GA2_HOST = Config.ga2Host();
    private static final String GA2_PORT = Config.ga2RepPort();

    private static final String ADDRESS_BIND = "tcp://" + ACTOR_BIND_HOST + ":" + ACTOR_PORT;
    private static final String ADDRESS_GA = "tcp://" + GA_HOST + ":" + GA_PORT;
    private static final String ADDRESS_GA2 = "tcp://" + GA2_HOST + ":" + GA2_PORT;

    private ZMQ.Context context;
    private ZMQ.Socket responder;
    private ZMQ.Socket socketGA;
    private ZMQ.Socket socketGA2;

    public static void main(String[] args) {
        new LoanActor().start();
    }

    public void start() {
        context = ZMQ.context(1);
        initSockets();

        Console.info("ACTOR-LOAN", "Activo en " + ADDRESS_BIND + ", GA=" + ADDRESS_GA + ", GA2=" + ADDRESS_GA2);

        while (!Thread.currentThread().isInterrupted()) {
            processRequests();
        }

        closeSockets();
    }

    private void initSockets() {
        responder = context.socket(ZMQ.REP);
        responder.bind(ADDRESS_BIND);

        socketGA = context.socket(ZMQ.REQ);
        socketGA.connect(ADDRESS_GA);
        socketGA.setReceiveTimeOut(3000);

        socketGA2 = context.socket(ZMQ.REQ);
        socketGA2.connect(ADDRESS_GA2);
        System.out.println(" Conectado a GA2 (fallback) en " + ADDRESS_GA2);
    }

    private void processRequests() {
        String solicitud = responder.recvStr();
        Console.info("ACTOR-LOAN", "Solicitud: " + solicitud);

        String bookId = RequestParser.extractBookId(solicitud);
        if (bookId == null || bookId.isEmpty()) {
            responder.send("Error: No se pudo extraer el ID del libro");
            return;
        }

        String mensajeDisponibilidad = "Disponibilidad?" + bookId;
        String respuestaGA;

        try {
            socketGA.send(mensajeDisponibilidad);
            respuestaGA = socketGA.recvStr();
            if (respuestaGA == null) {
                Console.warn("ACTOR-LOAN", "GA sin respuesta, fallback GA2");
                respuestaGA = consultarConGA2(mensajeDisponibilidad);
            } else {
                Console.info("ACTOR-LOAN", "Respuesta GA: " + respuestaGA);
            }
        } catch (Exception e) {
            Console.warn("ACTOR-LOAN", "Error GA: " + e.getMessage() + " -> fallback GA2");
            respuestaGA = consultarConGA2(mensajeDisponibilidad);
        }

        String respuestaFinal = handleGAResponse(respuestaGA);
        responder.send(respuestaFinal);
    }

    private String handleGAResponse(String respuestaGA) {
        if (Objects.equals(respuestaGA, "SI")) {
            Console.info("ACTOR-LOAN", "Préstamo confirmado");
            return "Préstamo confirmado";
        } else if (respuestaGA != null && respuestaGA.startsWith("NO")) {
            Console.info("ACTOR-LOAN", "Préstamo rechazado: " + respuestaGA);
            return "Préstamo rechazado: " + respuestaGA;
        } else {
            Console.warn("ACTOR-LOAN", "Respuesta desconocida GA: " + respuestaGA);
            return "Error: respuesta desconocida del GA";
        }
    }

    private String consultarConGA2(String mensaje) {
        try {
            Console.info("ACTOR-LOAN", "GA2 fallback -> " + mensaje);
            socketGA2.send(mensaje);
            String respuesta = socketGA2.recvStr();
            Console.info("ACTOR-LOAN", "Respuesta GA2: " + respuesta);
            return respuesta;
        } catch (Exception e) {
            Console.error("ACTOR-LOAN", "GA2 error: " + e.getMessage());
            return "Error: No se pudo comunicar ni con GA ni con GA2";
        }
    }

    private void closeSockets() {
        responder.close();
        socketGA.close();
        socketGA2.close();
        context.term();
        Console.info("ACTOR-LOAN", "Finalizado.");
    }
}
