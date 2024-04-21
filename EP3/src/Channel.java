import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

class DatagramInfo implements Serializable { // Esta classe representa os dados enviados entre os roteadores
  private int id; // Identificador do roteador
  private int[] vector; // Vetor de distâncias (indíce indica o id do roteador e o valor a distância até ele)

  public DatagramInfo(int id, int[] vector) {
    this.id = id;
    this.vector = vector;
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public int[] getVector() {
    return vector;
  }

  public void setVector(int[] vector) {
    this.vector = vector;
  }
}

public class Channel extends DatagramSocket { // Canal de comunicação
  private final boolean REMOVE_COLORS = false;
  private final boolean LOG_ENABLED = false; // Desativa log de mensagens utilizado para EPs anteriores
  private final int eliminateProbability = 0; // Esta variável define a probabilidade de uma mensagem ser eliminada

  private Random random = new Random();

  private ConcurrentHashMap<String, Integer> sendCount = new ConcurrentHashMap<>(); // Contagem de mensagens enviadas para cada destino
  private ConcurrentHashMap<String, Integer> eliminateCount = new ConcurrentHashMap<>(); // Contagem de mensagens eliminadas para cada destino

  private ConcurrentHashMap<String, Integer> receivedCount = new ConcurrentHashMap<>(); // Contagem de mensagens recebidas de cada destino

  public Channel(int port) throws SocketException {
    super(port);
  }

  public void send(DatagramPacket p) throws IOException { // Recebe pedidos de envio de segmentos UDP
    this.incrementCount(sendCount, getClientKey(p));
    this.applyErrorsAndSend(p);
  }

  public void send(DatagramInfo info, int destinationId) throws IOException { // Recebe pedidos de envio de segmentos UDP
    byte[] data = null;

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); 
      ObjectOutputStream oos = new ObjectOutputStream(bos)) {
      oos.writeObject(info);
      data = bos.toByteArray();
    }

    DatagramPacket p = new DatagramPacket(data, data.length, InetAddress.getLocalHost(), destinationId + 50000); // Instancia novo pacote com porta igual ao id do roteador de destino + 10000
    this.send(p);
  }

  public DatagramInfo receive() throws IOException { // Recebe a mensagem
    DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
    super.receive(p);
    incrementCount(receivedCount, getClientKey(p));
    byte[] data = p.getData();
    DatagramInfo datagramInfo = null;
    try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
      ObjectInputStream ois = new ObjectInputStream(bis)) {
      datagramInfo = (DatagramInfo) ois.readObject();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    return datagramInfo;
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

  private void logMessage(DatagramPacket p, String status, boolean received) { // Loga mensagens enviadas e recebidas
    if(LOG_ENABLED) {
      System.out.printf("%s: %s%n", received ? greenText("Recebida") : blueText("Enviada"), status);
    }
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

  public int getSentCount() {
    return sendCount.values().stream().mapToInt(Integer::intValue).sum();
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
