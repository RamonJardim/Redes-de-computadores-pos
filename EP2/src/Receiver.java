import java.net.SocketTimeoutException;
import java.util.Scanner;

public class Receiver {
  private static Scanner sc = new Scanner(System.in);
  private static final int DEFAULT_RECEIVER_PORT = 4321;
  private static final int DEFAULT_RECEIVER_TIMEOUT = 10000;

  public static void main(String[] args) throws Exception { // Lê a porta e recebe pacotes
    long start = System.currentTimeMillis();
    System.out.printf("Digite a porta do receiver (%d): ", DEFAULT_RECEIVER_PORT);
    String receiverPortString = sc.nextLine();
    int receiverPort = receiverPortString.equals("") ? DEFAULT_RECEIVER_PORT : Integer.parseInt(receiverPortString);

		try (ReliableChannel channel = new ReliableChannel(receiverPort)) {
      channel.setSoTimeout(DEFAULT_RECEIVER_TIMEOUT);
      while(true) {
      	try {
          channel.receive(1024); // Recebe o segmento UDP
        } catch (SocketTimeoutException e) {
          System.out.println("Timeout, finalizando receiver.");
          break;
        }
      }
      channel.consolidateAll(); // Exibe consolidação das mensagens
      long finish = System.currentTimeMillis();
      long timeElapsed = finish - start;
      System.out.println("Tempo total de execução: " + timeElapsed + "ms");
    } catch (Exception e) {
      System.out.println("Erro no recebimento de pacotes no receiver");
      e.printStackTrace();
    }
  }
}
