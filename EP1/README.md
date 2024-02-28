# EP1
Exercício programático 1 da disciplica de redes de computadores.

## Instruções para executar o programa
O projeto foi desenvolvido utilizando o VSCode e deve funcionar de forma direta utilizando o mesmo. Para compilar e executar o projeto, basta abrir o terminal na pasta raiz do projeto e executar os comandos abaixo.

Para iniciar o servidor:

```javac -cp ./lib/gson-2.10.1.jar ./src/Server.java ./src/Channel.java ./src/Client.java -d ./bin; java -cp "./lib/gson-2.10.1.jar:./bin/" Server```

Para iniciar o cliente:

```javac -cp ./lib/gson-2.10.1.jar ./src/Server.java ./src/Channel.java ./src/Client.java -d ./bin; java -cp "./lib/gson-2.10.1.jar:./bin/" Client```