import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;

// Matar o roteador durante a troca de informações acaba convergindo para um estado desatualizado
// Aumentar o valor de um link após a convergência não permite a convergência

public class Router extends Thread {
  private final boolean REMOVE_COLORS = false;
  private final int DELAY = 0;
  private final int TIMEOUT = 100;
  
  private int[] distanceVector;
  private int[] routingTable;
  private int myId;
  private int[] neighbors;
  private Sender sender;
  private Receiver receiver;
  private Spy spy;
  private Channel channel;
  private boolean finished = false;
  private boolean vectorChanged = true;
  private int sentPacketsCount;
  private int updateCount = 0;
  private int routerPadding;
  private int edgePadding;
  private String linkToChange;
  private int newWeight;
  private int routerToDrop;

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

  private int[] populateInitialRoutingTable(int[] distanceVector) {
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

  private void calculateNewDistanceVector(DatagramInfo info) {
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

  public void routerDown(int id) {
    if(Arrays.stream(neighbors).anyMatch(i -> i == id)) {
      distanceVector[id-1] = 0;
      routingTable[id-1] = 0;
      vectorChanged = true;
    }
  }

  public void modifyLink(int id, int newWeight) {
    if(Arrays.stream(neighbors).anyMatch(i -> i == id)) {
      distanceVector[id-1] = newWeight;
      vectorChanged = true;
    }
  }

  public static String distanceVectortoString(int[] vector, int padding) {
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
    if((myId == 1 || force) && false) {
      System.out.println("[" + String.format("%" + routerPadding + "d", myId) + "]: " + message);
    }
  }

  private void log(String message) {
    log(message, false);
  }

  private class Sender extends Thread {
    @Override
    public void run() {
      while (!finished) {
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

  private class Receiver extends Thread {
    @Override
    public void run() {
      try {
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
      }
    }
  }

  private class Spy extends Thread {
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