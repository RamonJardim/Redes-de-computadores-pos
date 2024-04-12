import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Sender {
  private static final int DEFAULT_SENDER_PORT = 9876;
  private static final int DEFAULT_RECEIVER_PORT = 4321;

  private static Scanner sc = new Scanner(System.in);

  public static void main(String[] args) { // Faz as leituras de ip e portas e envia os pacotes
    long start = System.currentTimeMillis();
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

    System.out.println("Digite a mensagem que deseja enviar (oi):");
    String message = sc.nextLine();
    message = message.equals("") ? "oi" : message;

    try (ReliableChannel channel = new ReliableChannel(clientPort)) {
      System.out.println("Digite quantas vezes deseja enviar (1000):");
      String receiverCountString = sc.nextLine();
      int messageCount = receiverCountString.equals("") ? 1000 : Integer.parseInt(receiverCountString);

      List<DatagramPacket> messages = new ArrayList<DatagramPacket>();
      for (int i = 0; i < messageCount; i++) {
        DatagramPacket p = new DatagramPacket(message.getBytes(), message.length());
        p.setAddress(ipAddress);
        p.setPort(receiverPort);
        messages.add(p);
      }

      channel.send(messages); // Envia as mensagens pelo canal confiável utilizando go back n

      channel.consolidateAll();  // Exibe consolidação das mensagens
      long finish = System.currentTimeMillis();
      long timeElapsed = finish - start;
      System.out.println("Tempo total de execução: " + timeElapsed + "ms");
    } catch (Exception e) {
      System.out.println("Erro no envio dos pacotes para o receiver");
      e.printStackTrace();
    }
  }
}
