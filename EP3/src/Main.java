import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
  private static Scanner sc = new Scanner(System.in);

  public static void main(String[] args) throws IOException, InterruptedException {
    // System.out.println("Deseja ler (0) ou gerar (1) uma tabela de adjacência? (0): ");
    // boolean generate = sc.nextLine().equals("1");
    // String[] adjacencyTable;
    // if(generate) {
    //   System.out.println("Digite o número de nós: ");
    //   int n = Integer.parseInt(sc.nextLine());
  
    //   System.out.println("Digite a porcentagem aproximada de conexões: ");
    //   int percentage = Integer.parseInt(sc.nextLine());
    //   adjacencyTable = generateAdjacencyTable(n, percentage);
    // } else {
    //   System.out.println("Digite o nome do arquivo (adjacency_table.txt): ");
    //   String fileName = sc.nextLine();
    //   fileName = fileName.equals("") ? "adjacency_table.txt" : fileName;

    //   adjacencyTable = Files.readString(Path.of(fileName)).split("\r\n");
    // }
    

    FileWriter fileWriter = new FileWriter("results.txt");
    PrintWriter printWriter = new PrintWriter(fileWriter);
    printWriter.println("nodes;links;sent_packets;std_sent;iterations;std_iterations");
    for (int nodes = 20; nodes > 0; nodes--) {
      for (int links = 100; links > 0; links-=10) {
        List<Integer> counts = new ArrayList<Integer>();
        List<Integer> iterations = new ArrayList<Integer>();
        for(int i = 0 ; i < 10 ; i++) {
          String[] adjacencyTable = generateAdjacencyTable(nodes, links);
          int[] result = run(adjacencyTable);
          counts.add(result[0]);
          iterations.add(result[1]);
        }

        double averageCount = counts.stream().mapToInt(i -> i).average().getAsDouble();
        double stdCounts = calculateStandardDeviation(counts);
        double averageIterations = iterations.stream().mapToInt(i -> i).average().getAsDouble();
        double stdIterations = calculateStandardDeviation(iterations);
        printWriter.println(nodes + ";" + links + ";" + averageCount + ";" + stdCounts + ";" + averageIterations + ";" + stdIterations);
        System.out.println("Média de pacotes enviados para " + nodes + " nós e " + links + "% de conexões: " + averageCount + " +/- " + stdCounts);
        System.out.println("Média de iterações para " + nodes + " nós e " + links + "% de conexões: " + averageIterations + " +/- " + stdIterations);
        System.out.println();
      }
    }

    printWriter.close();
    // String[] adjacencyTable = generateAdjacencyTable(0, 0);

    // run(adjacencyTable);
  }

  public static double calculateStandardDeviation(List<Integer> array) {

    // get the sum of array
    double sum = 0.0;
    for (double i : array) {
        sum += i;
    }

    // get the mean of array
    int length = array.size();
    double mean = sum / length;

    // calculate the standard deviation
    double standardDeviation = 0.0;
    for (double num : array) {
        standardDeviation += Math.pow(num - mean, 2);
    }

    return Math.sqrt(standardDeviation / length);
}

  private static int[] run(String[] nodes) throws InterruptedException {
    // System.out.println("Deseja alterar um link (1) ou derrubar um roteador (2)? (0 - não): ");
    // String opt = sc.nextLine();
    // int option = Integer.parseInt(opt == "" ? "0" : opt);

    int routerToDrop = -1;
    String linkToChange = "";
    int newWeight = -1;
    // if(option == 1) {
    //   System.out.println("Digite o id dos dois roteadores [No formato 'x y']: ");
    //   linkToChange = sc.nextLine();
    //   System.out.println("Digite o novo peso da conexão: ");
    //   newWeight = Integer.parseInt(sc.nextLine());
    // } else if(option == 2) {
    //   System.out.println("Digite o id do roteador a ser derrubado: ");
    //   routerToDrop = Integer.parseInt(sc.nextLine());
    // }

    int routerPadding = ("" + nodes.length).length();
    int edgePadding = 0;
    for (int i = 0 ; i < nodes.length ; i++) {
      int[] initialDV = convertToDistanceVector(nodes[i]);
      edgePadding = Math.max((Arrays.stream(initialDV).max().getAsInt() + "").length(), edgePadding);
    }
    edgePadding= Math.max(edgePadding, ("" + newWeight).length());

    Router[] routers = new Router[nodes.length];
    for (int i = 0 ; i < nodes.length ; i++) {
      routers[i] = new Router(Integer.parseInt(nodes[i].split(" ")[0]), convertToDistanceVector(nodes[i]), linkToChange, newWeight, routerToDrop, routerPadding, edgePadding);
      routers[i].start();
    }

    for (Router router : routers) {
      router.join();
    }

    // System.out.println();
    // System.out.println("Matriz de adjacência inicial: ");
    // for (int i = 0 ; i < nodes.length ; i++) {
    //   int[] initialDV = convertToDistanceVector(nodes[i]);
    //   System.out.println("[" + String.format("%"+ routerPadding +"s", nodes[i].split(" ")[0]) + "]: " + Router.distanceVectortoString(initialDV, edgePadding));
    // }
    // System.out.println();
    // System.out.println("Vetores de distância finais: ");
    
    int countSentPackets = 0;
    int countIterations = 0; 
    for (Router router : routers) {
      // System.out.println("[" + String.format("%"+ routerPadding +"s", router.getMyId()) + "]: " + router.getDistanceVectorString());
      countSentPackets += router.getSentPacketsCount();
      countIterations += router.getUpdateCount();
    }
    // System.out.println();
    // System.out.println("Total de pacotes enviados: " + countSentPackets);
    // System.out.println("Total de iterações: " + countIterations);

    return new int[]{countSentPackets, countIterations};
  }

  private static int[] convertToDistanceVector(String stringVector) {
    String[] values = stringVector.split(" ");
    int[] distanceVector = new int[values.length - 1];
    for (int i = 1; i < values.length; i++) {
      distanceVector[i-1] = Integer.parseInt(values[i]);
    }

    return distanceVector;
  }

  private static String[] generateAdjacencyTable(int n, int percentage) {
    int[][] adjacencyTable = new int[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = i; j < n; j++) {
        if (i == j) {
          adjacencyTable[i][j] = 0;
        } else {
          int value = ((int) (Math.random() * 100) + 1) * (Math.random() < (percentage / 100.0) ? 1 : 0);
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
