import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;

public class Router extends Thread {
  private final boolean REMOVE_COLORS = false;
  private final int DELAY = 100;
  private final int TIMEOUT = 5000;
  
  private int[] distanceVector;
  private int myId;
  private int[] neighbors;
  private Sender sender;
  private Receiver receiver;
  private Channel channel;
  private boolean finished = false;
  private boolean vectorChanged = true;
  private int sentPacketsCount;
  private int updateCount = 0;
  private int routerPadding;
  private int edgePadding;

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

  public Router(int myId, int[] distanceVector, int routerPadding, int edgePadding) {
    this.myId = myId;
    this.distanceVector = distanceVector;
    try {
      this.routerPadding = routerPadding;
      this.edgePadding = edgePadding;
      this.neighbors = getNeighbors(); // Obtém vizinhos usando lista inicial de vizinhos
      this.channel = new Channel(this.myId + 10000);
      this.channel.setSoTimeout(TIMEOUT);
      this.sender = new Sender();
      this.receiver = new Receiver();
    } catch (SocketException e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  @Override
  public void run() {
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
      if (receivedVector[i] != 0 && i != myId - 1) {
        int newDistance = distanceVector[info.getId() - 1] + receivedVector[i];
        if (newDistance < distanceVector[i] || distanceVector[i] == 0) {
          changed = true;
          updateCount++;
          distanceVector[i] = newDistance;
        }
      }
    }
    if(changed) {
      log(yellowText("Vetor de distância atualizado: " + distanceVectortoString(distanceVector, edgePadding)));
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

  private void log(String message) {
    if(myId == 1) {
      System.out.println("[" + String.format("%" + routerPadding + "d", myId) + "]: " + message);
    }
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
          sendVectorToNeighbors(channel); // Faz primeiro envio de vetor para vizinhos
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
          try {
            Thread.sleep(DELAY);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          DatagramInfo info = channel.receive();
          calculateNewDistanceVector(info);
        }
      } catch (IOException e) {
        if(e.getMessage().equals("Receive timed out")) {
          log(redText("Sem mudanças há " + TIMEOUT + "ms, encerrando..."));
          finished = true;
          // finalMessage("Vetor de distâncias final: " + Router.toString(distanceVector));
        } else {
          e.printStackTrace();
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