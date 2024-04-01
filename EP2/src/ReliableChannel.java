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

public class ReliableChannel extends DatagramSocket { // Canal de comunicação

  private class Config { // Classe para representar arquivo de configuração
    private int eliminateProbability;
    private int delayProbability;
    private int delayMS;
    private int duplicateProbability;
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
    public int getTimeout() {
      return timeout;
    }
  }

  private class ACKListener extends Thread { // Recebe os ACKs em paralelo
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
      while (this.channel.base <= this.channel.sendingDataPackets.size()) {
        try {
          channel.receiveACK();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  private class Timer extends Thread { // Timer para reenviar pacotes
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
        this.channel.timeout(this.packetNumber);
        this.channel.timer = null;
      } catch (InterruptedException e) {
        System.out.println(greenText("Timer para ACK " + this.getPacketNumber() + " parado"));
      }
    }
  }

  private class ACKSender extends Thread { // Envia os ACKs em paralelo
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

  private final boolean REMOVE_COLORS = false;

  private Config config;
  private Random random = new Random();
  private Timer timer;
  private int sequenceNumber = 1;
  private int timeout = -1;

  private List<DatagramPacket> sendingDataPackets = Collections.synchronizedList(new ArrayList<>()); // Buffer do remetente
  private int nextSeqNum = 1;
  private int base = 1;

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

    this.setSoTimeout(this.config.getTimeout() + 10000);
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

    startTimer(1);
    sendWindow(false); // Envia primeira janela de pacotes
    while(base <= sendingDataPackets.size()) { // Enquanto não confirmou todos os pacotes
      if(this.timeout != -1) {
        System.out.println(redText("Timeout") + " - Pacote [" + timeout + "]");
        startTimer(base);
        sendWindow(true);
        this.timeout = -1;
      } else {
        sendRange(); // Envia pacotes dentro do avanço da janela
      }
    }

    try {
      ackListener.interrupt(); // Para de ouvir os ACKs
    } catch (Exception e) {

    }
  }

  private void sendWindow(boolean isRetransmission) throws IOException { // Envia janela inicial de pacotes (ou retransmissão)
    int currentBase = base;
    for(int i = currentBase - 1 ; i < currentBase - 1 + config.getWindowSize() && i < sendingDataPackets.size() ; i++) {
      DatagramPacket p = sendingDataPackets.get(i);
      send(new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort()), i, false);
      if(!isRetransmission) nextSeqNum++;
    }
  }

  private void sendRange() throws IOException { // Envia pacotes dentro do avanço da janela
    int currentBase = base;
    for(int i = nextSeqNum ; i < currentBase + 1 + config.getWindowSize() && i < sendingDataPackets.size() + 1 ; i++) {
      DatagramPacket p = sendingDataPackets.get(i - 1);
      send(new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort()), i - 1, false);
      nextSeqNum++;
    }
  }

  private void startTimer(int seqNum) { // Inicia timer
    if(this.timer != null && this.timer.getPacketNumber() == seqNum) return; // Não reinicia o timer se pacote já está sendo monitorado
    System.out.println(yellowText("Iniciando timer para pacote " + (seqNum)));
    this.timer = new Timer(seqNum, config, this);
    timer.start();
  }

  private void stopTimer() { // Para o timer
    if(timer == null) return;
    timer.interrupt();
    this.timer = null;
  }

  private void timeout(int packetNumber) { // Seta flag de timeout
    this.timeout = packetNumber;
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
    byte[] isAckArray = Arrays.copyOfRange(data, 8, 12);
    int seqNumberInt = ByteBuffer.wrap(seqNumber).getInt();
    boolean isAck = ByteBuffer.wrap(isAckArray).getInt() == 1;
    byte[] message = Arrays.copyOfRange(data, 12, p.getLength());
    String messageString = new String(message, StandardCharsets.UTF_8);

    int sum = calculateChecksum(message, seqNumber, isAckArray);
    if(sum != ByteBuffer.wrap(checksum).getInt()) { // Verifica se o checksum está correto
      logMessage(p, redText("Corrompida/cortada"), true);
      incrementCount(receivedWithFailedIntegrityCount, getClientKey(p));
      return messageString;
    }

    if(seqNumberMap.getOrDefault(getClientKey(p), null) == null) { // Instancia o mapa de sequence numbers para o cliente caso não exista
      seqNumberMap.put(getClientKey(p), new ArrayList<Integer>());
    }

    ArrayList<Integer> seqNumList = seqNumberMap.get(getClientKey(p));
    if(seqNumList.contains(seqNumberInt)) { // Verifica se a mensagem é duplicada
      logMessage(p, redText("Duplicada"), true);
      incrementCount(receiveDuplicateCount, getClientKey(p));
    } else {
      if(!isAck) {
        if(expectedSeqNum == seqNumberInt) { // Verifica se o número de sequência é o esperado
          seqNumList.add(seqNumberInt);
          expectedSeqNum++;
          new ACKSender(this, seqNumberInt, p).start(); // Envia o ACK do pacote recebido
          logMessage(p, greenText("Entregue"), true);
        } else { // Se não for, adiciona o número de sequência ao mapa e não envia o ACK
          new ACKSender(this, expectedSeqNum - 1, p).start(); // Envia o ACK do próximo número de sequência esperado
          String expectedSeqNums = "";
          for(int i = expectedSeqNum ; i < seqNumberInt ; i++) { // Envia ACKs para os pacotes faltantes
            expectedSeqNums += i + " ";
          }
          logMessage(p, redText("Fora de ordem") + " - aguardando " + expectedSeqNums, true);
        }
      } else {
        seqNumList.add(seqNumberInt);
        boolean alreadyConfirmed = !(seqNumberInt >= this.base);
        logMessage(p, alreadyConfirmed ? "Já confirmado" : "Confirmado", true);
        this.base = !alreadyConfirmed ? seqNumberInt + 1 : this.base; // Se o ack for de um pacote ainda não confirmado, avança base para o próximo pacote
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

  public void receiveACK() throws IOException { // Recebe os ACKs
    this.receive(1024);
    if((this.timer != null && this.timer.getPacketNumber() < base) || base == sendingDataPackets.size()) {
      stopTimer(); // Para o timer se recebeu todos os ACKs da janela de envio
      if(base <= sendingDataPackets.size()) {
        startTimer(base); // Se não recebeu todos os ACKs, reinicia o timer
      }
    } else {
      validateAndStartTimer();
    }
  }

  private synchronized void validateAndStartTimer() {
    if(this.timer == null && base < sendingDataPackets.size()) { // Se não recebeu todos os ACKs, mas o timer não está rodando, reinicia o timer
      startTimer(base);
    }
  }

  private void buildSegment(DatagramPacket p, boolean isAck, int segmentSequenceNumber) { // Constrói o segmento UDP com o header definido
    byte[] messageBytes = p.getData();
    int definedSequenceNumber;
    if(segmentSequenceNumber != -1) { // Se o número de sequência for diferente de -1, utiliza o número de sequência fornecido (para ACKs e retransmissões)
      definedSequenceNumber = segmentSequenceNumber;
    } else { // Caso contrário, pega o próximo número de sequência
      definedSequenceNumber = getSequenceNumber();
    }

    byte[] seqNumber = java.nio.ByteBuffer.allocate(4).putInt(definedSequenceNumber).array();
    byte[] isAckBytes = java.nio.ByteBuffer.allocate(4).putInt(isAck ? 1 : 0).array();

    int sum = calculateChecksum(messageBytes, seqNumber, isAckBytes);

    byte[] checksum = java.nio.ByteBuffer.allocate(4).putInt(sum).array();

    byte[] data = new byte[checksum.length + seqNumber.length + isAckBytes.length + messageBytes.length];
    System.arraycopy(checksum, 0, data, 0, checksum.length);  // Primeiros 4 bytes da mensagem representam o checksum
    System.arraycopy(seqNumber, 0, data, checksum.length, seqNumber.length); // 4 Bytes seguintes representam o número de sequência
    System.arraycopy(isAckBytes, 0, data, checksum.length + seqNumber.length, isAckBytes.length); // 4 bytes seguinte indica se é ack
    System.arraycopy(messageBytes, 0, data, checksum.length + seqNumber.length + isAckBytes.length, messageBytes.length); // Restante da mensagem é o conteúdo de fato

    p.setData(data);
    p.setLength(data.length);
  }

  private void applyErrorsAndSend(DatagramPacket p) throws IOException { // Aplica falhas e envia a mensagem
    boolean eliminated = randomize(config.getEliminateProbability());
    boolean cut = randomize(config.getCutProbability());
    boolean delayed = randomize(config.getDelayProbability());
    boolean corrupted = randomize(config.getCorruptProbability());
    boolean duplicated = randomize(config.getDuplicateProbability());

    if(eliminated) { // Verifica se a mensagem deve ser eliminada
      incrementCount(eliminateCount, getClientKey(p));
      logMessage(p, redText("Eliminada"), false);
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
      logMessage(p, greenText("Normal"), false);
    }

    super.send(p); // Envia a mensagem
  }

  private void delayMessage(DatagramPacket p) { // Método para atrasar a mensagem
    logMessage(p, redText("Atrasada"), false);
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
    logMessage(p, redText("Corrompida"), false);
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
      logMessage(p, redText("Cortada"), false);
    }
  }

  private void duplicateMessage(DatagramPacket p) throws IOException { // Método para duplicar a mensagem
    logMessage(p, redText("Duplicada"), false);
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
    System.out.printf("%s [%d]: %s - \"%s\"%n", received ? greenText("Recebida") : blueText("Enviada"), getSegmentSeqNumber(p), status, getMessage(p));
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
    System.out.printf("Total de mensagens perdidas (Sequence Number não encontrado): %d%n", seqNumlist.size() == 0 ? 0 : countMissingMessages(seqNumlist));
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
    byte[] isAckArray = Arrays.copyOfRange(data, 8, 12);
    int isAck = ByteBuffer.wrap(isAckArray).getInt();
    if(isAck == 1) {
      return yellowText("ACK");
    }
    return new String(Arrays.copyOfRange(data, 12, data.length), StandardCharsets.UTF_8);
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

  private String yellowText(String text) {
    return REMOVE_COLORS ? text : "\u001B[33m" + text + "\u001B[0m";
  }
}
