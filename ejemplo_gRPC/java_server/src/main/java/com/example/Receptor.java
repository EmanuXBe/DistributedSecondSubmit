package com.example;

import java.io.IOException;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

public class Receptor {
    private final static int serverPort = 12345;
    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = Grpc.newServerBuilderForPort(serverPort, InsecureServerCredentials.create())
                .addService(new MessageServiceImpl())
                .addService(new SortServiceImpl())
                .build();

        server.start();
        System.out.println("Server started on port 12345");
        server.awaitTermination();
    }

    static class MessageServiceImpl extends MessageServiceGrpc.MessageServiceImplBase {
        @Override
        public void sendMessage(Message request, StreamObserver<Message> responseObserver) {
            System.out.println("Mensaje recibido: " + request.getMessage());
            System.out.println("IP del mensajero: " + request.getIp());

            Message response = Message.newBuilder()
                    .setMessage(request.getMessage())
                    .setIp(request.getIp())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    static class SortServiceImpl extends SortServiceGrpc.SortServiceImplBase {
        @Override
        public void sortArray(Array request, StreamObserver<Array> responseObserver) {
            System.out.println("Numeros recibidos: " + request.getDataList());

            Array response = Array.newBuilder()
                    .addAllData(request.getDataList().stream().sorted().toList())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
