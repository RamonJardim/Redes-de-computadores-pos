import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Main {
  private static Scanner sc = new Scanner(System.in);

  public static void main(String[] args) throws Exception {
    System.out.println("Deseja ler (0) ou gerar (1) uma tabela de adjacência? (0): ");
    boolean generate = sc.nextLine().equals("1");

    if(generate) {
      generateAdjacencyTableMenu();
    } else {
      System.out.println("Digite o nome do arquivo (adjacency_table.txt): ");
      String fileName = sc.nextLine();
      fileName = fileName.equals("") ? "adjacency_table.txt" : fileName;

      String adjacencyTable = Files.readString(Path.of(fileName));

      String[] nodes = adjacencyTable.split("\r\n");
      Router[] routers = new Router[nodes.length];
      for (int i = 0 ; i < nodes.length ; i++) {
        routers[i] = new Router(Integer.parseInt(nodes[i].charAt(0)+""), convertToDistanceVector(nodes[i]));
        routers[i].start();
      }

      for (Router router : routers) {
        router.join();
      }
    }
  }

  private static int[] convertToDistanceVector(String stringVector) {
    String[] values = stringVector.split(" ");
    int[] distanceVector = new int[values.length - 1];
    for (int i = 1; i < values.length; i++) {
      distanceVector[i-1] = Integer.parseInt(values[i]);
    }

    return distanceVector;
  }

  private static void generateAdjacencyTableMenu() {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'generateAdjacencyTableMenu'");
  }
}
