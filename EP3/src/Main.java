import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;

public class Main {
  private static Scanner sc = new Scanner(System.in);

  public static void main(String[] args) throws IOException, InterruptedException {
    System.out.println("Deseja ler (0) ou gerar (1) uma tabela de adjacência? (0): ");
    boolean generate = sc.nextLine().equals("1");
    String[] adjacencyTable;
    if(generate) {
      adjacencyTable = generateAdjacencyTable();
    } else {
      System.out.println("Digite o nome do arquivo (adjacency_table.txt): ");
      String fileName = sc.nextLine();
      fileName = fileName.equals("") ? "adjacency_table.txt" : fileName;

      adjacencyTable = Files.readString(Path.of(fileName)).split("\r\n");
    }

    run(adjacencyTable);
  }

  private static void run(String[] nodes) throws InterruptedException {
    int routerPadding = ("" + nodes.length).length();
    int edgePadding = 0;
    for (int i = 0 ; i < nodes.length ; i++) {
      int[] initialDV = convertToDistanceVector(nodes[i]);
      edgePadding = Math.max((Arrays.stream(initialDV).max().getAsInt() + "").length(), edgePadding);
    }

    Router[] routers = new Router[nodes.length];
    for (int i = 0 ; i < nodes.length ; i++) {
      routers[i] = new Router(Integer.parseInt(nodes[i].split(" ")[0]), convertToDistanceVector(nodes[i]), routerPadding, edgePadding);
      routers[i].start();
    }

    for (Router router : routers) {
      router.join();
    }

    System.out.println();
    System.out.println("Matriz de adjacência inicial: ");
    for (int i = 0 ; i < nodes.length ; i++) {
      int[] initialDV = convertToDistanceVector(nodes[i]);
      System.out.println("[" + String.format("%"+ routerPadding +"s", nodes[i].split(" ")[0]) + "]: " + Router.distanceVectortoString(initialDV, edgePadding));
    }
    System.out.println();
    System.out.println("Vetores de distância finais: ");
    
    int countSentPackets = 0;
    int countIterations = 0; 
    for (Router router : routers) {
      System.out.println("[" + String.format("%"+ routerPadding +"s", router.getMyId()) + "]: " + router.getDistanceVectorString());
      countSentPackets += router.getSentPacketsCount();
      countIterations += router.getUpdateCount();
    }
    System.out.println();
    System.out.println("Total de pacotes enviados: " + countSentPackets);
    System.out.println("Total de iterações: " + countIterations);
  }

  private static int[] convertToDistanceVector(String stringVector) {
    String[] values = stringVector.split(" ");
    int[] distanceVector = new int[values.length - 1];
    for (int i = 1; i < values.length; i++) {
      distanceVector[i-1] = Integer.parseInt(values[i]);
    }

    return distanceVector;
  }

  private static String[] generateAdjacencyTable() {
    System.out.println("Digite o número de nós: ");
    int n = Integer.parseInt(sc.nextLine());

    System.out.println("Digite a porcentagem aproximada de conexões: ");
    int percentage = Integer.parseInt(sc.nextLine());

    int[][] adjacencyTable = new int[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = i; j < n; j++) {
        if (i == j) {
          adjacencyTable[i][j] = 0;
        } else {
          int value = ((int) (Math.random() * 100) + 1) * (Math.random() < percentage / 100.0 ? 1 : 0);
          adjacencyTable[i][j] = value;
          adjacencyTable[j][i] = value;
        }
      }
    }

    String[] adjacencyTableString = new String[n];
    for (int i = 0; i < adjacencyTableString.length; i++) {
      String line = "" + (i + 1);
      for (int j = 0; j < n; j++) {
        line += " " + adjacencyTable[i][j];
      }
      adjacencyTableString[i] = line;
    }

    return adjacencyTableString;
  }
}
