import java.net.SocketTimeoutException;
import java.util.Scanner;

public class Server { // Servidor UDP
  private static Scanner sc = new Scanner(System.in);
  private static final int DEFAULT_SERVER_PORT = 4321;
  private static final int DEFAULT_SERVER_TIMEOUT = 10000;

  public static void main(String[] args) throws Exception { // Lê a porta do servidor e recebe pacotes
    System.out.printf("Digite a porta do servidor (%d): ", DEFAULT_SERVER_PORT);
    String serverPortString = sc.nextLine();
    int serverPort = serverPortString.equals("") ? DEFAULT_SERVER_PORT : Integer.parseInt(serverPortString);

		try (Channel channel = new Channel(serverPort)) {
      channel.setSoTimeout(DEFAULT_SERVER_TIMEOUT);
      while(true) {
      	try {
          channel.receive(1024); // Recebe o segmento UDP
        } catch (SocketTimeoutException e) {
          System.out.println("Timeout, finalizando servidor.");
          break;
        }
      }
      channel.consolidateAll(); // Exibe consolidação das mensagens
    } catch (Exception e) {
      System.out.println("Erro no recebiumento de pacotes no servidor");
      e.printStackTrace();
    }
  }
}
