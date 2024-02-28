import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;

class Config { // Classe para representar arquivo de configuração
  private int eliminateProbability;
  private int delayProbability;
  private int delayMS;
  private int duplicateProbability;
  private int corruptBytes;
  private int corruptProbability;
  private int cutProbability;
  private int cutBytes;

  public int getEliminateProbability() {
    return eliminateProbability;
  }
  public int getDelayProbability() {
    return delayProbability;
  }
  public int getDelayMS() {
    return delayMS;
  }
  public int getDuplicateProbability() {
    return duplicateProbability;
  }
  public int getCorruptBytes() {
    return corruptBytes;
  }
  public int getCorruptProbability() {
    return corruptProbability;
  }
  public int getCutProbability() {
    return cutProbability;
  }
  public int getCutBytes() {
    return cutBytes;
  }
}

public class Channel extends DatagramSocket {

  class ACKSender extends Thread {
    private Channel channel;
    private int seqNumber;
    private DatagramPacket p;
  
    public ACKSender(Channel channel, int seqNumber, DatagramPacket p) {
      this.channel = channel;
      this.seqNumber = seqNumber;
      this.p = p;
    }

    @Override
    public void run() {
      try {
        this.sendACK();
      } catch (IOException e) {
        System.out.println("Erro ao receber ACK");
        e.printStackTrace();
      }
    }
  
    private void sendACK() throws IOException {
      try {
        channel.sendACK(p, seqNumber);
      } catch (Exception e) {
        System.out.println("Erro ao enviar ACK");
        e.printStackTrace();
      }
    }
  }


  private Config config;
  private Random random = new Random();
  private int sequenceNumber = 1;

  private ConcurrentHashMap<String, Integer> sendCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> eliminateCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> delayCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> duplicateCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> corruptCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> cutCount = new ConcurrentHashMap<>();

  private ConcurrentHashMap<String, Integer> receivedCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> receivedWithFailedIntegrityCount = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, Integer> receiveDuplicateCount = new ConcurrentHashMap<>();

  private ConcurrentHashMap<String, ArrayList<Integer>> seqNumberMap = new ConcurrentHashMap<>();

  public Channel(int port) throws SocketException {
    super(port);
    Gson gson = new Gson();
    try {
      this.config = gson.fromJson(Files.readString(Path.of("config.json")), Config.class); // Leitura do arquivo config.json
    } catch (IOException e) {
      System.out.println("Could not read config file");
      e.printStackTrace();
      System.exit(1);
    }
  }

  private synchronized int getSequenceNumber() { // Retorna o sequence number da próxima mensagem a ser enviada
    return sequenceNumber++;
  }

  @Override public void send(DatagramPacket p) throws IOException {
    this.send(p, -1);
  }

  public void send(DatagramPacket p, int sequenceNumber) throws IOException { // Recebe pedidos de envio de segmentos UDP
    incrementCount(sendCount, getClientKey(p));
    byte[] messageBytes = p.getData();
    int definedSequenceNumber;
    if(sequenceNumber != -1) { // Se o número de sequência for diferente de -1, utiliza o número de sequência fornecido (para ACKs, por exemplo)
      definedSequenceNumber = sequenceNumber;
    } else { // Caso contrário, pega o próximo número de sequência
      definedSequenceNumber = getSequenceNumber();
    }

    byte[] seqNumber = java.nio.ByteBuffer.allocate(4).putInt(definedSequenceNumber).array();

    int sum = calculateChecksum(messageBytes, seqNumber);

    byte[] checksum = java.nio.ByteBuffer.allocate(4).putInt(sum).array();

    byte[] data = new byte[checksum.length + seqNumber.length + messageBytes.length];
    System.arraycopy(checksum, 0, data, 0, checksum.length);  // Primeiros 4 bytes da mensagem representam o checksum
    System.arraycopy(seqNumber, 0, data, checksum.length, seqNumber.length); // 4 Bytes seguintes representam o número de sequência
    System.arraycopy(messageBytes, 0, data, checksum.length + seqNumber.length, messageBytes.length); // Restante da mensagem é o conteúdo de fato

    p.setData(data);
    p.setLength(data.length);

    if(randomize(config.getEliminateProbability())) { // Verifica se a mensagem deve ser eliminada
      incrementCount(eliminateCount, getClientKey(p));
      logMessage(p, "Eliminada", false);
      return;
    }

    if(randomize(config.getCutProbability())) { // Verifica se a mensagem deve ser cortada - Sempre é cortada se > 1024 bytes
      this.cutMessage(p);
    }
    
    if(randomize(config.getDelayProbability())) { // Verifica se a mensagem deve ser atrasada - Sempre é atrasada
      this.delayMessage(p);
    }
    
    if(randomize(config.getCorruptProbability())) { // Verifica se a mensagem deve ser corrompida
      this.corruptMesage(p);
    } else if(randomize(config.getDuplicateProbability())) { // Verifica se a mensagem deve ser duplicada
      this.duplicateMessage(p);
    }

    super.send(p); // Envia a mensagem
  }


  public String receive(int length) throws IOException { // Recebe a mensagem
    DatagramPacket p = new DatagramPacket(new byte[length], length);
    super.receive(p);
    incrementCount(receivedCount, getClientKey(p));
    byte[] data = p.getData();
    byte[] checksum = Arrays.copyOfRange(data, 0, 4);
    byte[] seqNumber = Arrays.copyOfRange(data, 4, 8);
    int seqNumberInt = ByteBuffer.wrap(seqNumber).getInt();
    byte[] message = Arrays.copyOfRange(data, 8, p.getLength());
    String messageString = new String(message, StandardCharsets.UTF_8);

    int sum = calculateChecksum(message, seqNumber);
    if(sum != ByteBuffer.wrap(checksum).getInt()) { // Verifica se o checksum está correto
      logMessage(p, "Corrompida/cortada", true);
      incrementCount(receivedWithFailedIntegrityCount, getClientKey(p));
      return messageString;
    }

    if(seqNumberMap.get(getClientKey(p)) == null) {
      seqNumberMap.put(getClientKey(p), new ArrayList<Integer>());
    }

    ArrayList<Integer> seqNumList = seqNumberMap.get(getClientKey(p));
    if(seqNumList.contains(seqNumberInt)) { // Verifica se a mensagem é duplicada
      logMessage(p, "Duplicada", true);
      incrementCount(receiveDuplicateCount, getClientKey(p));
    } else {
      seqNumList.add(seqNumberInt);
      if(!messageString.equals("ACK")) {
        new ACKSender(this, seqNumberInt, p).start(); // Envia o ACK
      }
    }

    return messageString;
  }

  private void delayMessage(DatagramPacket p) { // Método para atrasar a mensagem
    // logMessage(p, "Atrasada");
    try {
      Thread.sleep(config.getDelayMS());
      incrementCount(delayCount, getClientKey(p));
    } catch(InterruptedException e) {
      System.out.println("Error while generating delay");
      e.printStackTrace();
      System.exit(1);
    }
  }

  private void corruptMesage(DatagramPacket p) throws IOException { // Método para corromper a mensagem
    logMessage(p, "Corrompida", false);
    byte[] data = p.getData();
    data[random.nextInt(data.length)] += 1;
    incrementCount(corruptCount, getClientKey(p));
  }

  private void cutMessage(DatagramPacket p) { // Método para cortar a mensagem
    if(p.getLength() > config.getCutBytes()) {
      logMessage(p, "Cortada", false);
      byte[] newData = new byte[config.getCutBytes()];
      byte[] oldData = p.getData();
      for (int i = 0; i < newData.length; i++) {
        newData[i] = oldData[i];
      }
      p.setData(newData);
      p.setLength(newData.length);
      incrementCount(cutCount, getClientKey(p));
    }
  }

  private void duplicateMessage(DatagramPacket p) throws IOException { // Método para duplicar a mensagem
    logMessage(p, "Duplicada", false);
    super.send(p);
    incrementCount(duplicateCount, getClientKey(p));
  }

  private boolean randomize(int probability) {
    return random.nextInt(100) < probability;
  }

  private String getClientKey(DatagramPacket p) {
    return p.getAddress().getHostAddress() + ":" + p.getPort();
  }

  private int getSegmentSeqNumber(DatagramPacket p) {
    byte[] data = p.getData();
    byte[] seqNumber = Arrays.copyOfRange(data, 4, 8);

    return ByteBuffer.wrap(seqNumber).getInt();
  }

  private synchronized void incrementCount(Map<String, Integer> map, String key) {
    map.put(key, map.getOrDefault(key, 0) + 1);
  }

  private void logMessage(DatagramPacket p, String status, boolean received) {
    System.out.printf("%s [%d]: %s - \"%s\"%n", received ? "Recebida" : "Enviada", getSegmentSeqNumber(p), status, getMessage(p));
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
    System.out.printf("Total de mensagens enviadas: %d%n", sendCount.get(key) == null ? 0 : sendCount.get(key));
    System.out.printf("Total de mensagens eliminadas: %d%n", eliminateCount.get(key) == null ? 0 : eliminateCount.get(key));
    // System.out.printf("Total de mensagens atrasadas: %d%n", delayCount.get(key) == null ? 0 : delayCount.get(key));
    System.out.printf("Total de mensagens duplicadas: %d%n", duplicateCount.get(key) == null ? 0 : duplicateCount.get(key));
    System.out.printf("Total de mensagens corrompidas: %d%n", corruptCount.get(key) == null ? 0 : corruptCount.get(key));
    System.out.printf("Total de mensagens cortadas: %d%n", cutCount.get(key) == null ? 0 : cutCount.get(key));

    sendCount.put(key, 0);
    eliminateCount.put(key, 0);
    delayCount.put(key, 0);
    duplicateCount.put(key, 0);
    corruptCount.put(key, 0);
    cutCount.put(key, 0);
  }

  private void consolidateReceived(String key) { // Consolida as estatísticas de recebimento
    ArrayList<Integer> seqNumlist = seqNumberMap.get(key);
    System.out.printf("Total de mensagens recebidas: %d%n", receivedCount.get(key) == null ? 0 : receivedCount.get(key));
    System.out.printf("Total de mensagens perdidas (Sequence Number não encontrado): %d%n", seqNumlist == null ? 0 : countMissingMessages(seqNumlist) - receivedWithFailedIntegrityCount.get(key));
    System.out.printf("Total de mensagens duplicadas: %d%n", receiveDuplicateCount.get(key) == null ? 0 : receiveDuplicateCount.get(key));
    System.out.printf("Total de mensagens corrompidas/cortadas (checksum falhou): %d%n", receivedWithFailedIntegrityCount.get(key) == null ? 0 : receivedWithFailedIntegrityCount.get(key));

    sendCount.put(key, 0);
    eliminateCount.put(key, 0);
    delayCount.put(key, 0);
    duplicateCount.put(key, 0);
    corruptCount.put(key, 0);
    cutCount.put(key, 0);
  }

  private int countMissingMessages(ArrayList<Integer> seqNumlist) {
    Collections.sort(seqNumlist);
    int missing = 0;
    for(int i = 1; i < seqNumlist.size(); i++) {
      if(seqNumlist.get(i) - seqNumlist.get(i - 1) > 1) {
        missing += seqNumlist.get(i) - seqNumlist.get(i - 1) - 1;
      }
    }
    missing += seqNumlist.get(0) - 1;

    return missing;
  }

  private int calculateChecksum(byte[] ...data) {
    int sum = 0;
    for(byte[] bArray : data) {
      for(byte b : bArray) {
        sum += b;
      }
    }

    return sum;
  }

  private void sendACK(DatagramPacket p, int seqNumber) throws IOException {  // Envia o ACK
    DatagramPacket ack = new DatagramPacket("ACK".getBytes(), 3);
    ack.setAddress(p.getAddress());
    ack.setPort(p.getPort());
    this.send(ack, seqNumber);
  }

  private String getMessage(DatagramPacket p) {
    byte[] data = p.getData();

    return new String(Arrays.copyOfRange(data, 8, data.length), StandardCharsets.UTF_8);
  }

  public void receiveACK() throws IOException {
    this.receive(1024);
  }
}
