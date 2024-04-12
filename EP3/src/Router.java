import java.net.DatagramPacket;
import java.net.SocketException;

public class Router extends Thread {
  private int myId;
  private int[] distanceVector;

  public Router(int myId, int[] distanceVector) {
    this.myId = myId;
    this.distanceVector = distanceVector;
  }

  @Override
  public void run() {
    try (DatagramInfo channel = new DatagramInfo(myId + 10000)) {
      sendVectorToNeighbors(channel); // Faz primeiro envio de vetor para vizinhos
    } catch (SocketException e) {
      e.printStackTrace();
    }
  }

  private void sendVectorToNeighbors(DatagramInfo channel) {
    for (int i = 0; i < distanceVector.length; i++) {
      if (distanceVector[i] != 0) {
        DatagramPacket packet = new DatagramPacket(buf, length, address, port);
        channel.send(packet);
      }
    }
  }
}
