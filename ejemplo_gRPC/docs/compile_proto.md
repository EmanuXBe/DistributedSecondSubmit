# Compilación del `.proto`

## Java
El proyecto está configurado para usar Maven para la compilación del archivo `.proto`, en concreto usa el plugin [protobuf-maven-plugin](https://www.xolstice.org/protobuf-maven-plugin/). Por lo tanto solo es necesario ejecutar:

```bash
mvn clean install
```
Si quieres usar Gradle puedes consultar [grpc-java README](https://github.com/grpc/grpc-java/blob/master/README.md) donde detallan otras formas de compilación.

Si no se usa Maven o Gradle, se puede hacer uso del compilador proto3 como explican en la [documentación oficial](https://grpc.io/docs/languages/java/basics/).

## Python
```bash
python -m grpc_tools.protoc -Iproto --python_out=python_server --pyi_out=python_server --grpc_python_out=python_server proto/*.proto
```
## Ejecución
Una vez generados los archivos, ya puedes ejecutar el servidor en Python `mensajero.py` y el servidor en Java `Receptor`