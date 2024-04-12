import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class DatagramInfo extends DatagramSocket { // Canal de comunicação
  private final boolean REMOVE_COLORS = false;
  private final int eliminateProbability = 4;

  private Random random = new Random();

  private ConcurrentHashMap<String, Integer> sendCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> eliminateCount = new ConcurrentHashMap<>();

  private ConcurrentHashMap<String, Integer> receivedCount = new ConcurrentHashMap<>();

  public DatagramInfo(int port) throws SocketException {
    super(port);
  }

  public void send(DatagramPacket p) throws IOException { // Recebe pedidos de envio de segmentos UDP
    this.incrementCount(sendCount, getClientKey(p));
    this.applyErrorsAndSend(p);
  }

  public String receive(int length) throws IOException { // Recebe a mensagem
    DatagramPacket p = new DatagramPacket(new byte[length], length);
    super.receive(p);
    incrementCount(receivedCount, getClientKey(p));
    byte[] data = p.getData();

    return messageString;

    /* Passar a enviar uma classe (DatagramInfo) com myId e vetor:
     * DatagramInfo vira Channel
     * DatagramInfo vira classe interna de Channel
    */
  }

  private void applyErrorsAndSend(DatagramPacket p) throws IOException { // Aplica falhas e envia a mensagem
    boolean eliminated = randomize(eliminateProbability);

    if(eliminated) { // Verifica se a mensagem deve ser eliminada
      incrementCount(eliminateCount, getClientKey(p));
      logMessage(p, redText("Eliminada"), false);
      return;
    }

    if(!eliminated) {
      logMessage(p, greenText("Normal"), false);
    }

    super.send(p); // Envia a mensagem
  }

  private boolean randomize(int probability) {
    return random.nextInt(100) < probability;
  }

  private String getClientKey(DatagramPacket p) {
    return p.getAddress().getHostAddress() + ":" + p.getPort();
  }

  private synchronized void incrementCount(Map<String, Integer> map, String key) {
    map.put(key, map.getOrDefault(key, 0) + 1);
  }

  private void logMessage(DatagramPacket p, String status, boolean received) {
    System.out.printf("%s: %s%n", received ? greenText("Recebida") : blueText("Enviada"), status);
  }

  public void consolidateAll() { // Consolida todas as estatísticas
    System.out.printf("Resumo de mensagens enviadas:%n");
    sendCount.keySet().forEach((String key) -> {
      System.out.printf("-----------%s-----------%n", key);
      consolidateSent(key);
    });
    System.out.printf("------------------------------------%n");

    System.out.printf("Resumo de mensagens recebidas:%n");
    receivedCount.keySet().forEach((String key) -> {
      System.out.printf("-----------%s-----------%n", key);
      consolidateReceived(key);
    });
    System.out.printf("------------------------------------%n");
  }

  private void consolidateSent(String key) { // Consolida as estatísticas de envio
    System.out.printf("Total de mensagens enviadas: %d%n", sendCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens eliminadas: %d%n", eliminateCount.getOrDefault(key, 0));

    sendCount.put(key, 0);
    eliminateCount.put(key, 0);
  }

  private void consolidateReceived(String key) { // Consolida as estatísticas de recebimento
    System.out.printf("Total de mensagens recebidas: %d%n", receivedCount.getOrDefault(key, 0));

    sendCount.put(key, 0);
    eliminateCount.put(key, 0);
  }

  private String redText(String text) {
    return REMOVE_COLORS ? text : "\u001B[31m" + text + "\u001B[0m";
  }

  private String blueText(String text) {
    return REMOVE_COLORS ? text : "\u001B[34m" + text + "\u001B[0m";
  }

  private String greenText(String text) {
    return REMOVE_COLORS ? text : "\u001B[32m" + text + "\u001B[0m";
  }
}
