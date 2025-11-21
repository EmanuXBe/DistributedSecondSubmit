import grpc
import example_pb2 as spb2
import example_pb2_grpc as sgrpc
import socket


def run(array, message, ip):
    print("Arreglo sin ordenar: ", array)
    print("Mensaje a enviar: ", message)
    # Conexión con el receptor
    with grpc.insecure_channel('localhost:12345') as channel:
        # Conexión con el servicio
        stub = sgrpc.SortServiceStub(channel)
        response = stub.SortArray(spb2.Array(data=array))
        # Impresion del arreglo, accediendo al arreglo de la información recibida
        print("Arreglo ordenado:", response.data)

        stub = sgrpc.MessageServiceStub(channel)
        response = stub.SendMessage(spb2.Message(message=message, ip=ip))
        # Impresion del mensaje, accediendo al mensaje de la información recibida
        print("Mensaje enviado:", response.message)
        print("IP del mensajero enviada:", response.ip)



def initialize():
    n = input("Ingrese el tamaño del arreglo a enviar: ")
    array = []
    print("Introduzca los numeros, separados por la tecla 'Enter', del arreglo de tamaño ", n)

    for i in range(int(n)):
        m = int(input())
        array.append(m)

    message = input("Ingrese el mensaje que desea adicionar: ")

    # Obtener la IP del mensajero
    ip = socket.gethostbyname(socket.gethostname())

    run(array, message, ip)


if __name__ == '__main__':
    initialize()