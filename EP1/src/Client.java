import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

class ParallelSender extends Thread { // Envia os pacotes em paralelo
  private Channel channel;
  private DatagramPacket packet;

  public ParallelSender(Channel channel, DatagramPacket packet) {
    this.channel = channel;
    this.packet = packet;
  }
  @Override
  public void run() {
    try {
      send();
    } catch (IOException e) {
      System.out.println("Erro ao enviar pacote para o servidor");
      e.printStackTrace();
    }
  }

  private void send() throws IOException {
    channel.send(packet);
  }
}

class ACKListener extends Thread { // Recebe os ACKs em paralelo
  private Channel channel;

  public ACKListener(Channel channel) {
    this.channel = channel;
  }
  @Override
  public void run() {
    try {
      receiveACK();
    } catch (IOException e) {
      System.out.println("Erro ao receber ACK");
      e.printStackTrace();
    }
  }

  private void receiveACK() throws IOException {
    while(true) {
      try {
        channel.setSoTimeout(1000);
        channel.receiveACK();
      } catch (Exception e) {
        break;
      }
    }
  }
}

public class Client { // Cliente UDP
  private static final int DEFAULT_CLIENT_PORT = 9876;
  private static final int DEFAULT_SERVER_PORT = 4321;

  private static Scanner sc = new Scanner(System.in);
  private static Scanner scInt = new Scanner(System.in);

  public static void main(String[] args) { // Faz as leituras de ip e portas e envia os pacotes
    System.out.printf("Digite a porta do cliente (%d): ", DEFAULT_CLIENT_PORT);
    String clientPortString = sc.nextLine();
    int clientPort = clientPortString.equals("") ? DEFAULT_CLIENT_PORT : Integer.parseInt(clientPortString);


    System.out.print("Digite o IP do servidor (127.0.0.1): ");
    String serverIP = sc.nextLine();
    serverIP = serverIP.equals("") ? "127.0.0.1" : serverIP;

    InetAddress ipAddress = null;
    try {
      ipAddress = InetAddress.getByName(serverIP);
    } catch (UnknownHostException e) {
      System.out.println("Erro ao buscar IP da máquina");
      e.printStackTrace();
      System.exit(1);
    }

    System.out.printf("Digite a porta do servidor (%d): ", DEFAULT_SERVER_PORT);
    String serverPortString = sc.nextLine();
    int serverPort = serverPortString.equals("") ? DEFAULT_SERVER_PORT : Integer.parseInt(serverPortString);

    System.out.println("Digite quantas mensagens deseja enviar (0 para ler as linhas de um arquivo):");
    int messageCount = scInt.nextInt();

    System.out.println("Digite 0 para enviar de forma sequencial ou 1 para enviar de forma paralela: ");
    boolean parallel = scInt.nextInt() == 1;
    
    try (Channel channel = new Channel(clientPort)) {
      DatagramPacket[] messages = null;
      if(messageCount != 0) {
        System.out.printf("Digite as %d mensagens (digite o caminho de um arquivo entre [] para ler um arquivo inteiro como uma das mensagens)%n", messageCount);
        messages = new DatagramPacket[messageCount];
        for (int i = 0; i < messageCount; i++) {
          System.out.printf("%d: ", i+1);
          String message = sc.nextLine();
          if(message.charAt(0) == '[' && message.charAt(message.length() - 1) == ']') {
            message = Files.readString(Path.of(message.substring(1, message.length() - 1)));
          }
          messages[i] = new DatagramPacket(message.getBytes(), message.length());
          messages[i].setAddress(ipAddress);
          messages[i].setPort(serverPort);
        }
      } else {
        System.out.print("Digite o caminho do arquivo: ");
        List<String> messagesString = Files.readAllLines(Path.of(sc.nextLine()));
        messages = new DatagramPacket[messagesString.size()];
        for (int i = 0; i < messagesString.size(); i++) {
          messages[i] = new DatagramPacket(messagesString.get(i).getBytes(), messagesString.get(i).length());
          messages[i].setAddress(ipAddress);
          messages[i].setPort(serverPort);
        }
      }

      ACKListener ackListener = new ACKListener(channel);
      ackListener.start();

      if(parallel) {
        Thread[] threadList = new Thread[messages.length];
        for (int i = 0; i < messages.length; i++) { // Envia as mensagens em paralelo
          threadList[i] = new ParallelSender(channel, messages[i]);
          threadList[i].start();
        }

        for(int i = 0; i < threadList.length; i++) { // Aguarda todas as threads terminarem
          threadList[i].join();
        }
      } else {
        for (int i = 0; i < messages.length; i++) { // Envia as mensagens em sequência
          channel.send(messages[i]);
        }
      }

      ackListener.join();

      channel.consolidateAll();  // Exibe consolidação das mensagens
    } catch (Exception e) {
      System.out.println("Erro no envio dos pacotes para o servidor");
      e.printStackTrace();
    }
  }
}
