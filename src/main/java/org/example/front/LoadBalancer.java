package org.example.front;

import org.example.config.Config;
import org.example.util.CommandRouter;
import org.example.util.Console;
import org.zeromq.ZMQ;

public class LoadBalancer {

    private static final String UNKNOWN_RESPONSE = "Solicitud no reconocida";
    private static final String GC_BIND_HOST = Config.gcBindHost();
    private static final String PS_PORT = Config.gcPsPort();
    private static final String PUB_PORT = Config.gcPubPort();
    private static final String ACTOR_HOST = Config.actorHost();
    private static final String ACTOR_PORT = Config.actorPort();

    private static final String ADDRESS_PS = "tcp://" + GC_BIND_HOST + ":" + PS_PORT;
    private static final String ADDRESS_PUB = "tcp://" + GC_BIND_HOST + ":" + PUB_PORT;
    private static final String ADDRESS_ACTOR = "tcp://" + ACTOR_HOST + ":" + ACTOR_PORT;

    private ZMQ.Context context;
    private ZMQ.Socket socketPS;
    private ZMQ.Socket publisher;
    private ZMQ.Socket loanActor;
    private final CommandRouter router = new CommandRouter(UNKNOWN_RESPONSE)
        .onPrefix("DEVOLVER", this::handleReturn)
        .onPrefix("RENOVAR", this::handleRenewal)
        .onPrefix("PRESTAMO", this::handleLoan)
        .onExact("PING", req -> "PONG");

    public static void main(String[] args) throws InterruptedException {
        new LoadBalancer().start();
    }

    public void start() throws InterruptedException {
        context = ZMQ.context(1);
        initSockets();
        Console.info("GC", "Listening " + ADDRESS_PS + " (REQ) / " + ADDRESS_PUB + " (PUB)");

        while (!Thread.currentThread().isInterrupted()) {
            String request = socketPS.recvStr();
            Console.info("GC", "Solicitud: " + request);

            String response = handleRequest(request);
            socketPS.send(response, 0);

            Thread.sleep(100);
        }

        closeSockets();
    }

    private void initSockets() {
        socketPS = context.socket(ZMQ.REP);
        socketPS.bind(ADDRESS_PS);

        publisher = context.socket(ZMQ.PUB);
        publisher.bind(ADDRESS_PUB);

        loanActor = context.socket(ZMQ.REQ);
        loanActor.connect(ADDRESS_ACTOR);
    }

    private String handleRequest(String request) {
        if (request == null || request.isEmpty()) {
            return "Solicitud vacía o nula";
        }

        String response = router.dispatch(request);
        if (UNKNOWN_RESPONSE.equals(response)) {
            Console.warn("GC", "Solicitud no reconocida: " + request);
        }
        return response;
    }

    private String handleReturn(String request) {
        Console.info("GC", "DEVOLVER -> publish");
        publisher.send("DEVOLUCION " + request);
        return "Devolución aceptada, gracias.";
    }

    private String handleRenewal(String request) {
        String newDate = getRenewalDate();
        Console.info("GC", "RENOVAR -> publish, nueva fecha " + newDate);
        publisher.send("RENOVACION " + request);
        return "Renovación aceptada, nueva fecha: " + newDate;
    }

    private String handleLoan(String request) {
        Console.info("GC", "PRESTAMO -> actor");
        loanActor.send(request, 0);
        String loanResponse = loanActor.recvStr();
        Console.info("GC", "Respuesta actor: " + loanResponse);
        return loanResponse;
    }

    private String getRenewalDate() {
        java.time.LocalDate newDate = java.time.LocalDate.now().plusWeeks(1);
        return newDate.toString();
    }

    private void closeSockets() {
        socketPS.close();
        publisher.close();
        loanActor.close();
        context.term();
        Console.info("GC", "Sockets cerrados.");
    }
}
