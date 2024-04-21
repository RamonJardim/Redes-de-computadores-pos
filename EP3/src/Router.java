import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;

public class Router extends Thread { // Classe que representa um roteador
  private final boolean REMOVE_COLORS = false;
  private final int DELAY = 0; // Tempo que o roteador aguarda entre envios de pacotes
  private final int TIMEOUT = 5000; // Tempo máximo de espera por um pacote (finaliza execução após este tempo sem receber pacotes)
  
  private int[] distanceVector; // Vetor de distâncias
  private int[] routingTable; // Tabela de roteamento
  private int myId; // Identificador do roteador
  private int[] neighbors; // Vizinhos
  private Sender sender; // Thread para enviar informações
  private Receiver receiver; // Thread para receber informações
  private Spy spy; // Thread para derrubar um roteador ou alterar um link
  private Channel channel; // Canal de comunicação
  private boolean finished = false; // Indica se o roteador foi derrubado ou se terminou execução
  private boolean vectorChanged = true; // Indica se o vetor de distâncias foi alterado
  private int sentPacketsCount; // Contador de pacotes enviados
  private int updateCount = 0; // Contador de atualizações no vetor de distâncias (iterações)
  private int routerPadding; // Tamanho do padding para o id do roteador (para alinhamento na impressão da matriz de adjacência)
  private int edgePadding; // Tamanho do padding para o peso da conexão (para alinhamento na impressão da matriz de adjacência)
  private String linkToChange; // Link a ser alterado
  private int newWeight; // Novo peso do link
  private int routerToDrop; // Roteador a ser derrubado

  public int getUpdateCount() {
    return updateCount;
  }

  public int getSentPacketsCount() {
    return sentPacketsCount;
  }

  public int getMyId() {
    return myId;
  }

  public String getDistanceVectorString() {
    return Router.distanceVectortoString(distanceVector, edgePadding);
  }

  public Router(int myId, int[] distanceVector, String linkToChange, int newWeight, int routerToDrop, int routerPadding, int edgePadding) {
    this.myId = myId;
    this.distanceVector = distanceVector;
    try {
      this.routingTable = populateInitialRoutingTable(distanceVector);
      this.linkToChange = linkToChange;
      this.newWeight = newWeight;
      this.routerToDrop = routerToDrop;
      this.routerPadding = routerPadding;
      this.edgePadding = edgePadding;
      this.neighbors = getNeighbors(); // Obtém vizinhos usando lista inicial de vizinhos
      this.channel = new Channel(this.myId + 10000);
      this.channel.setSoTimeout(TIMEOUT);
      this.sender = new Sender();
      this.receiver = new Receiver();
      this.spy = new Spy();
    } catch (SocketException e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private int[] populateInitialRoutingTable(int[] distanceVector) { // Preenche a tabela de roteamento inicial
    int[] routingTable = new int[distanceVector.length];
    for (int i = 0; i < distanceVector.length; i++) {
      routingTable[i] = distanceVector[i] == 0 ? 0 : i + 1;
    }
    return routingTable;
  }

  @Override
  public void run() {
    spy.start();
    sender.start();
    receiver.start();
    try {
      sender.join();
      receiver.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
      System.exit(1);
    } finally {
      sentPacketsCount = channel.getSentCount();
      channel.close();
    }
  }

  private int[] getNeighbors() {
    ArrayList<Integer> neighbors = new ArrayList<Integer>();
    for (int i = 0; i < this.distanceVector.length; i++) {
      if (distanceVector[i] != 0) {
        neighbors.add(i + 1);
      }
    }
    return neighbors.stream().mapToInt(i -> i).toArray();
  }

  private void calculateNewDistanceVector(DatagramInfo info) { // Calcula o novo vetor de distâncias
    int[] receivedVector = info.getVector();
    log(greenText("Novo vetor de distâncias recebido de [" + String.format("%" + routerPadding + "d", info.getId()) + "]: " + Router.distanceVectortoString(receivedVector, edgePadding)));
    boolean changed = false;
    for (int i = 0; i < receivedVector.length; i++) {
      if(i + 1 == myId || info.getId() == i + 1) continue;
      if (receivedVector[i] != 0) {
        int newDistance = distanceVector[info.getId() - 1] + receivedVector[i];
        if (newDistance < distanceVector[i] || distanceVector[i] == 0 || (routingTable[i] == info.getId() && distanceVector[i] != newDistance)) {
          changed = true;
          updateCount++;
          routingTable[i] = info.getId();
          distanceVector[i] = newDistance;
        }
      } else if(receivedVector[i] == 0 && routingTable[i] == info.getId() && distanceVector[i] != 0) {
        changed = true;
        updateCount++;
        routingTable[i] = 0;
        distanceVector[i] = 0;
      }
    }
    if(changed) {
      log(yellowText("Vetor de distância atualizado: " + distanceVectortoString(distanceVector, edgePadding)));
      vectorChanged = true;
    }
  }

  public void routerDown(int id) { // Derruba um roteador
    if(Arrays.stream(neighbors).anyMatch(i -> i == id)) {
      distanceVector[id-1] = 0;
      routingTable[id-1] = 0;
      vectorChanged = true;
    }
  }

  public void modifyLink(int id, int newWeight) { // Altera o peso de uma conexão
    if(Arrays.stream(neighbors).anyMatch(i -> i == id)) {
      distanceVector[id-1] = newWeight;
      vectorChanged = true;
    }
  }

  public static String distanceVectortoString(int[] vector, int padding) { // Converte um vetor de distâncias para string
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < vector.length; i++) {
      sb.append(String.format("%" + padding + "d", vector[i]));
      if (i < vector.length - 1) {
        sb.append(" ");
      }
    }
    return sb.toString();
  }

  private void log(String message, boolean force) {
    if(myId == 1 || force) {
      System.out.println("[" + String.format("%" + routerPadding + "d", myId) + "]: " + message);
    }
  }

  private void log(String message) {
    log(message, false);
  }

  private class Sender extends Thread { // Thread para enviar informações para os vizinhos
    @Override
    public void run() {
      while (!finished) {
        Thread.yield();
        try {
          Thread.sleep(DELAY);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if(vectorChanged) {
          sendVectorToNeighbors(channel);
          vectorChanged = false;
        }
      }
    }

    private void sendVectorToNeighbors(Channel channel) {
      log(blueText("Enviando novo vetor de distâncias: " + Router.distanceVectortoString(distanceVector, edgePadding)));
      for (int i = 0; i < neighbors.length; i++) {
        try {
          DatagramInfo packet = new DatagramInfo(myId, distanceVector);
          channel.send(packet, neighbors[i]);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  private class Receiver extends Thread { // Thread para receber as informações dos outros roteadores
    @Override
    public void run() {
      try {
        Thread.sleep(2000);
        while (!finished) {
          DatagramInfo info = channel.receive();
          calculateNewDistanceVector(info);
        }
      } catch (IOException e) {
        if(e.getMessage().equals("Receive timed out")) {
          log(redText("Sem mudanças há " + TIMEOUT + "ms, encerrando..."));
          finished = true;
        } else {
          e.printStackTrace();
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  private class Spy extends Thread { // Esta thread é reponsável por derrubar um roteador ou alterar um link
    @Override
    public void run() {
      try {
        Thread.sleep(TIMEOUT/3);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      if(routerToDrop != -1) {
        if(routerToDrop == myId) {
          log(redText("Roteador derrubado, encerrando..."), true);
          finished = true;
          try {
            sender.join();
            receiver.join();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          for (int i = 0; i < distanceVector.length; i++) {
            distanceVector[i] = 0;
          }
        } else {
          routerDown(routerToDrop);
        }
      } else if(linkToChange != "") {
        String[] link = linkToChange.split(" ");
        int id1 = Integer.parseInt(link[0]);
        int id2 = Integer.parseInt(link[1]);
        if(id1 == myId) {
          log(redText(String.format("Alterando link %d %d para %d", id1, id2, newWeight)), true);
          modifyLink(id2, newWeight);
        } else if(id2 == myId) {
          modifyLink(id1, newWeight);
        }
      }
    }
  }

  private String redText(String text) {
    return REMOVE_COLORS ? text : "\u001B[31m" + text + "\u001B[0m";
  }

  private String yellowText(String text) {
    return REMOVE_COLORS ? text : "\u001B[33m" + text + "\u001B[0m";
  }

  private String blueText(String text) {
    return REMOVE_COLORS ? text : "\u001B[34m" + text + "\u001B[0m";
  }

  private String greenText(String text) {
    return REMOVE_COLORS ? text : "\u001B[32m" + text + "\u001B[0m";
  }
}