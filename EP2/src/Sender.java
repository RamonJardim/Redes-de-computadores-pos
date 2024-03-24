import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Sender {
  private static final int DEFAULT_SENDER_PORT = 9876;
  private static final int DEFAULT_RECEIVER_PORT = 4321;

  private static Scanner sc = new Scanner(System.in);
  private static Scanner scInt = new Scanner(System.in);

  public static void main(String[] args) { // Faz as leituras de ip e portas e envia os pacotes
    System.out.printf("Digite a porta do sender (%d): ", DEFAULT_SENDER_PORT);
    String clientPortString = sc.nextLine();
    int clientPort = clientPortString.equals("") ? DEFAULT_SENDER_PORT : Integer.parseInt(clientPortString);


    System.out.print("Digite o IP do receiver (127.0.0.1): ");
    String receiverIP = sc.nextLine();
    receiverIP = receiverIP.equals("") ? "127.0.0.1" : receiverIP;

    InetAddress ipAddress = null;
    try {
      ipAddress = InetAddress.getByName(receiverIP);
    } catch (UnknownHostException e) {
      System.out.println("Erro ao buscar IP da máquina");
      e.printStackTrace();
      System.exit(1);
    }

    System.out.printf("Digite a porta do receiver (%d): ", DEFAULT_RECEIVER_PORT);
    String receiverPortString = sc.nextLine();
    int receiverPort = receiverPortString.equals("") ? DEFAULT_RECEIVER_PORT : Integer.parseInt(receiverPortString);

    System.out.println("Digite a mensagem que deseja enviar (vazio para ler as linhas de um arquivo):");
    String message = sc.nextLine();

    try (ReliableChannel channel = new ReliableChannel(clientPort)) {
      List<String> messagesString = new ArrayList<>();
      if(message.equals("")) {
        System.out.print("Digite o caminho do arquivo: ");
        messagesString = Files.readAllLines(Path.of(sc.nextLine()));
      } else {
        System.out.println("Digite quantas vezes deseja enviar (1000):");
        int messageCount = scInt.nextInt();
        for (int i = 0; i < messageCount; i++) {
          messagesString.add(message);
        }
      }
    
      DatagramPacket[] messages = new DatagramPacket[messagesString.size()];
      for (int i = 0; i < messages.length; i++) {
        messages[i] = new DatagramPacket(message.getBytes(), message.length());
        messages[i].setAddress(ipAddress);
        messages[i].setPort(receiverPort);
      }

      ACKListener ackListener = new ACKListener(channel);
      ackListener.start();

      for (int i = 0; i < messages.length; i++) { // Envia as mensagens em sequência
        channel.send(messages[i]);
      }

      ackListener.join();

      channel.consolidateAll();  // Exibe consolidação das mensagens
    } catch (Exception e) {
      System.out.println("Erro no envio dos pacotes para o receiver");
      e.printStackTrace();
    }
  }
}
