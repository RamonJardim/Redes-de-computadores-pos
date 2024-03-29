// Talvez usar loops para simular uma estrutura de eventos
// Timeouts subtrações de system.time

// Thread esperando pacotes e atualizando maps e envia ack como um async
// Thread enviando pacotes e atualizando maps


import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
  private int windowSize;
  private int timeout;

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
  public int getWindowSize() {
    return windowSize;
  }
  public void setWindowSize(int windowSize) {
    this.windowSize = windowSize;
  }
  public int getTimeout() {
    return timeout;
  }
}

class ACKListener extends Thread { // Recebe os ACKs em paralelo
  private ReliableChannel channel;

  public ACKListener(ReliableChannel channel) {
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
    while (true) {
      try {
        channel.receiveACK();
      } catch (Exception e) {
        break;
      }
    }
  }
}

public class ReliableChannel extends DatagramSocket { // Canal de comunicação

  class Timer extends Thread { // Timer para reenviar pacotes
    private int packetNumber;
    private ReliableChannel channel;
    private Config config;
  
    public Timer(int packetNumber, Config config, ReliableChannel channel) {
      this.packetNumber = packetNumber;
      this.config = config;
      this.channel = channel;
    }
  
    public int getPacketNumber() {
      return packetNumber;
    }
  
    @Override
    public void run() {
      try {
        Thread.sleep(config.getTimeout());
        this.channel.timeout();
        this.channel.timer = null;
      } catch (InterruptedException e) {
        System.out.println("Erro no timer");
        e.printStackTrace();
      }
    }
  }

  class ACKSender extends Thread { // Envia os ACKs em paralelo
    private ReliableChannel channel;
    private int seqNumber;
    private DatagramPacket p;
  
    public ACKSender(ReliableChannel channel, int seqNumber, DatagramPacket p) {
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
  private Timer timer;
  private int sequenceNumber = 1;
  private boolean timeout = false;

  private List<DatagramPacket> receivedDataPackets = Collections.synchronizedList(new ArrayList<>());
  private List<DatagramPacket> sendingDataPackets = Collections.synchronizedList(new ArrayList<>());
  private int nextseqnum = 0;
  private int base = 0;

  private int expectedSeqNum = 1;

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

  public ReliableChannel(int port) throws SocketException {
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

  public void send(List<DatagramPacket> ps) throws IOException {
    for(int i = 1 ; i <= ps.size() ; i++) {
      DatagramPacket p = ps.get(i - 1);
      this.buildSegment(p, false, i);
      sendingDataPackets.add(p);
    }

    ACKListener ackListener = new ACKListener(this);
    ackListener.start();

    sendWindow(false); // Envia primeira janela de pacotes
    while(base != sendingDataPackets.size()) { // Enquanto não confirmou todos os pacotes
      if(this.timeout) {
        System.out.println("Timeout, reenviando pacotes");
        startTimer();
        this.timeout = false;
        sendWindow(true);
      } else {
        sendRange();
      }
    }

    ackListener.interrupt(); // Para de ouvir os ACKs
  }

  private void sendWindow(boolean isRetransmission) throws IOException {
    for(int i = base ; i < base + config.getWindowSize() && i < sendingDataPackets.size() ; i++) {
      DatagramPacket p = sendingDataPackets.get(i);
      send(new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort()), i, false);
      if(base == nextseqnum) {
        startTimer();
      }
      if(!isRetransmission) nextseqnum++;
    }
  }

  private void sendRange() throws IOException {
    for(int i = nextseqnum ; i < base + config.getWindowSize() && i < sendingDataPackets.size() ; i++) {
      DatagramPacket p = sendingDataPackets.get(i);
      send(new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort()), i, false);
      nextseqnum++;
    }
  }

  private void startTimer() {
    this.timer = new Timer(nextseqnum, config, this);
    timer.start();
  }

  private void stopTimer() {
    timer.interrupt();
    this.timer = null;
  }

  private void timeout() {
    this.timeout = true;
  }

  public void send(DatagramPacket p, int segmentSequenceNumber, boolean isAck) throws IOException { // Recebe pedidos de envio de segmentos UDP
    this.incrementCount(sendCount, getClientKey(p));
    if(isAck) this.buildSegment(p, isAck, segmentSequenceNumber);
    this.applyErrorsAndSend(p);
  }

  public String receive(int length) throws IOException { // Recebe a mensagem
    DatagramPacket p = new DatagramPacket(new byte[length], length);
    super.receive(p);
    incrementCount(receivedCount, getClientKey(p));
    byte[] data = p.getData();
    byte[] checksum = Arrays.copyOfRange(data, 0, 4);
    byte[] seqNumber = Arrays.copyOfRange(data, 4, 8);
    byte[] isAckArray = Arrays.copyOfRange(data, 8, 9);
    int seqNumberInt = ByteBuffer.wrap(seqNumber).getInt();
    boolean isAck = ByteBuffer.wrap(isAckArray).getInt() == 1;
    byte[] message = Arrays.copyOfRange(data, 9, p.getLength());
    String messageString = new String(message, StandardCharsets.UTF_8);

    int sum = calculateChecksum(message, seqNumber);
    if(sum != ByteBuffer.wrap(checksum).getInt()) { // Verifica se o checksum está correto
      logMessage(p, "Corrompida/cortada", true);
      incrementCount(receivedWithFailedIntegrityCount, getClientKey(p));
      return messageString;
    }

    if(seqNumberMap.getOrDefault(getClientKey(p), null) == null) { // Instancia o mapa de sequence numbers para o cliente caso não exista
      seqNumberMap.put(getClientKey(p), new ArrayList<Integer>());
    }

    ArrayList<Integer> seqNumList = seqNumberMap.get(getClientKey(p));
    if(seqNumList.contains(seqNumberInt)) { // Verifica se a mensagem é duplicada
      logMessage(p, "Duplicada", true);
      incrementCount(receiveDuplicateCount, getClientKey(p));
    } else {
      if(!isAck) {
        if(expectedSeqNum == seqNumberInt) { // Verifica se o número de sequência é o esperado
          seqNumList.add(seqNumberInt);
          expectedSeqNum++;
          new ACKSender(this, seqNumberInt, p).start(); // Envia o ACK do pacote recebido
          logMessage(p, "Entregue", true);
        } else { // Se não for, adiciona o número de sequência ao mapa e não envia o ACK
          new ACKSender(this, expectedSeqNum, p).start(); // Envia o ACK do próximo número de sequência esperado
          logMessage(p, "Fora de ordem", true);
        }
      } else {
        this.base = seqNumberInt + 1 > this.base ? seqNumberInt + 1 : this.base;
      }
    }

    return messageString;
  }

  private void sendACK(DatagramPacket p, int seqNumber) throws IOException {  // Envia o ACK
    DatagramPacket ack = new DatagramPacket(new byte[0], 0);
    ack.setAddress(p.getAddress());
    ack.setPort(p.getPort());
    this.send(ack, seqNumber, true);
  }

  public void receiveACK() throws IOException {
    this.receive(1024);
    if(base == nextseqnum) {
      stopTimer(); // Para o timer se recebeu todos os ACKs da janela de envio
    } else {
      sendWindow(false); // Envia pacotes dentro do avanço da janela
      if(this.timer == null) {
        startTimer();
      } else {
        System.out.println("Timer já está rodando para pacote " + timer.getPacketNumber());
      }
    }
  }

  private void buildSegment(DatagramPacket p, boolean isAck, int segmentSequenceNumber) {
    byte[] messageBytes = p.getData();
    int definedSequenceNumber;
    if(segmentSequenceNumber != -1) { // Se o número de sequência for diferente de -1, utiliza o número de sequência fornecido (para ACKs e retransmissões)
      definedSequenceNumber = segmentSequenceNumber;
    } else { // Caso contrário, pega o próximo número de sequência
      definedSequenceNumber = getSequenceNumber();
    }

    byte[] seqNumber = java.nio.ByteBuffer.allocate(4).putInt(definedSequenceNumber).array();

    int sum = calculateChecksum(messageBytes, seqNumber);

    byte[] checksum = java.nio.ByteBuffer.allocate(4).putInt(sum).array();
    byte[] isAckBytes = java.nio.ByteBuffer.allocate(4).putInt(isAck ? 1 : 0).array();

    byte[] data = new byte[checksum.length + seqNumber.length + isAckBytes.length + messageBytes.length];
    System.arraycopy(checksum, 0, data, 0, checksum.length);  // Primeiros 4 bytes da mensagem representam o checksum
    System.arraycopy(seqNumber, 0, data, checksum.length, seqNumber.length); // 4 Bytes seguintes representam o número de sequência
    System.arraycopy(isAckBytes, 0, data, checksum.length + seqNumber.length, isAckBytes.length); // byte seguinte indica se é ack
    System.arraycopy(messageBytes, 0, data, checksum.length + seqNumber.length + isAckBytes.length, messageBytes.length); // Restante da mensagem é o conteúdo de fato

    p.setData(data);
    p.setLength(data.length);
  }

  private void applyErrorsAndSend(DatagramPacket p) throws IOException {
    boolean eliminated = randomize(config.getEliminateProbability());
    boolean cut = randomize(config.getCutProbability());
    boolean delayed = randomize(config.getDelayProbability());
    boolean corrupted = randomize(config.getCorruptProbability());
    boolean duplicated = randomize(config.getDuplicateProbability());

    if(eliminated) { // Verifica se a mensagem deve ser eliminada
      incrementCount(eliminateCount, getClientKey(p));
      logMessage(p, "Eliminada", false);
      return;
    }

    if(cut) { // Verifica se a mensagem deve ser cortada - Sempre é cortada se > 1024 bytes
      this.cutMessage(p);
    }
    
    if(delayed) { // Verifica se a mensagem deve ser atrasada
      this.delayMessage(p);
    }
    
    if(corrupted) { // Verifica se a mensagem deve ser corrompida
      this.corruptMesage(p);
    }
    
    if(duplicated) { // Verifica se a mensagem deve ser duplicada
      this.duplicateMessage(p);
    }

    if(!eliminated && !delayed && !corrupted && !duplicated) {
      logMessage(p, "Normal", false);
    }

    super.send(p); // Envia a mensagem
  }

  private void delayMessage(DatagramPacket p) { // Método para atrasar a mensagem
    logMessage(p, "Atrasada", false);
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
    byte[] data = p.getData();
    data[random.nextInt(data.length)] += 1;
    incrementCount(corruptCount, getClientKey(p));
    logMessage(p, "Corrompida", false);
  }

  private void cutMessage(DatagramPacket p) { // Método para cortar a mensagem
    if(p.getLength() > config.getCutBytes()) {
      byte[] newData = new byte[config.getCutBytes()];
      byte[] oldData = p.getData();
      for (int i = 0; i < newData.length; i++) {
        newData[i] = oldData[i];
      }
      p.setData(newData);
      p.setLength(newData.length);
      incrementCount(cutCount, getClientKey(p));
      logMessage(p, "Cortada", false);
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
    System.out.printf("Total de mensagens enviadas: %d%n", sendCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens eliminadas: %d%n", eliminateCount.getOrDefault(key, 0));
    // System.out.printf("Total de mensagens atrasadas: %d%n", delayCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens duplicadas: %d%n", duplicateCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens corrompidas: %d%n", corruptCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens cortadas: %d%n", cutCount.getOrDefault(key, 0));

    sendCount.put(key, 0);
    eliminateCount.put(key, 0);
    delayCount.put(key, 0);
    duplicateCount.put(key, 0);
    corruptCount.put(key, 0);
    cutCount.put(key, 0);
  }

  private void consolidateReceived(String key) { // Consolida as estatísticas de recebimento
    ArrayList<Integer> seqNumlist = seqNumberMap.getOrDefault(key, new ArrayList<>());
    System.out.printf("Total de mensagens recebidas: %d%n", receivedCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens perdidas (Sequence Number não encontrado): %d%n", seqNumlist.size() == 0 ? 0 : countMissingMessages(seqNumlist) - receivedWithFailedIntegrityCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens duplicadas: %d%n", receiveDuplicateCount.getOrDefault(key, 0));
    System.out.printf("Total de mensagens corrompidas/cortadas (checksum falhou): %d%n", receivedWithFailedIntegrityCount.getOrDefault(key, 0));

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

  private String getMessage(DatagramPacket p) {
    byte[] data = p.getData();

    return new String(Arrays.copyOfRange(data, 12, data.length), StandardCharsets.UTF_8);
  }
}
