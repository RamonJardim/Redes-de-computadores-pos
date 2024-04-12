# EP2
Exercício programático 2 da disciplica de redes de computadores.

## Instruções para executar o programa
O projeto foi desenvolvido utilizando o VSCode e deve funcionar de forma direta utilizando o mesmo. Para compilar e executar o projeto, basta abrir o terminal na pasta raiz do projeto e executar os comandos abaixo.

Para iniciar o receiver:

```javac -cp ./lib/gson-2.10.1.jar ./src/Receiver.java ./src/ReliableChannel.java ./src/Sender.java -d ./bin; java -cp "./lib/gson-2.10.1.jar:./bin/" Receiver```

Para iniciar o sender:

```javac -cp ./lib/gson-2.10.1.jar ./src/Receiver.java ./src/ReliableChannel.java ./src/Sender.java -d ./bin; java -cp "./lib/gson-2.10.1.jar:./bin/" Sender```