/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2015 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
package spade.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import jline.ConsoleReader;
import spade.core.AbstractVertex;
import spade.core.AbstractEdge;
import spade.core.Graph;
import spade.core.Settings;

public class Query {

    private static PrintStream outputStream;
    private static PrintStream SPADEQueryIn;
    private static ObjectInputStream SPADEQueryOut;
    private static final String SPADE_ROOT = Settings.getProperty("spade_root");
    private static final String historyFile = SPADE_ROOT + "cfg/query.history";
    private static final String COMMAND_PROMPT = "-> ";
    private static HashMap<String, Graph> graphObjects;
    private static HashMap<String, String> graphExpressions;
    private static String QUERY_STORAGE = "Neo4j";
    // Members for creating secure sockets
    private static KeyStore clientKeyStorePrivate;
    private static KeyStore serverKeyStorePublic;
    private static SSLSocketFactory sslSocketFactory;

    private static void setupKeyStores() throws Exception {
        String serverPublicPath = SPADE_ROOT + "cfg/ssl/server.public";
        String clientPrivatePath = SPADE_ROOT + "cfg/ssl/client.private";

        serverKeyStorePublic = KeyStore.getInstance("JKS");
        serverKeyStorePublic.load(new FileInputStream(serverPublicPath), "public".toCharArray());
        clientKeyStorePrivate = KeyStore.getInstance("JKS");
        clientKeyStorePrivate.load(new FileInputStream(clientPrivatePath), "private".toCharArray());
    }

    private static void setupClientSSLContext() throws Exception {
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextInt();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(serverKeyStorePublic);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(clientKeyStorePrivate, "private".toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), secureRandom);
        sslSocketFactory = sslContext.getSocketFactory();
    }

    public static void main(String args[]) {
        // Set up context for secure connections
        try {
            setupKeyStores();
            setupClientSSLContext();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        outputStream = System.out;
        graphObjects = new HashMap<>();
        graphExpressions = new HashMap<>();

        try {
            String host = "localhost";
            int port = Integer.parseInt(Settings.getProperty("local_query_port"));
            SSLSocket remoteSocket = (SSLSocket) sslSocketFactory.createSocket(host, port);

            OutputStream outStream = remoteSocket.getOutputStream();
            InputStream inStream = remoteSocket.getInputStream();
            SPADEQueryOut = new ObjectInputStream(inStream);
            SPADEQueryIn = new PrintStream(outStream);
        } catch (NumberFormatException | IOException exception) {
            outputStream.println("Error connecting to SPADE");
            System.exit(-1);
        }

        try {
            outputStream.println("");
            outputStream.println("SPADE 2.0 Query Client");
            outputStream.println("");

            // Set up command history and tab completion.
            ConsoleReader commandReader = new ConsoleReader();
            try {
                commandReader.getHistory().setHistoryFile(new File(historyFile));
            } catch (Exception ex) {
                // Ignore
            }

            SPADEQueryIn.println("");
            String commands = (String) SPADEQueryOut.readObject();
            System.out.println(commands + "\n");

            while (true) {
                try {
                    outputStream.print(COMMAND_PROMPT);
                    String line = commandReader.readLine();
                    if (line.equals("exit")) {
                        SPADEQueryIn.println(line);
                        break;
                    } else if (line.equals("list")) {
                        System.out.println("-------------------------------------------------");
                        System.out.println("Graph\t\t | Expression");
                        System.out.println("-------------------------------------------------");
                        for (Map.Entry<String, Graph> currentEntry : graphObjects.entrySet()) {
                            String key = currentEntry.getKey();
                            String expression = graphExpressions.get(key);
                            System.out.println(key + "\t\t | " + expression);
                        }
                        System.out.println("-------------------------------------------------");
                        System.out.println();
                    } else if (line.trim().startsWith("storage")) {
                        String[] tokens = line.trim().split("\\s+");
                        QUERY_STORAGE = tokens[1];
                    } else if (line.startsWith("export")) {
                        String[] tokens = line.split("\\s+");
                        graphObjects.get(tokens[1]).exportGraph(tokens[2]);
                    } else {
                        parseQuery(line);
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            }
        } catch (IOException | ClassNotFoundException exception) {
            exception.printStackTrace();
        }
    }

    private static void parseQuery(String input) {
        // Accepts input of the following form and generates the corresponding
        // query expression to pass to the Query class:
        //      function(arguments)
        // Examples:
        //   <result> = getVertices(expression)
        //   <result> = getEdges(source vertex id, destination vertex id)
        //   <result> = getPaths(source vertex id, destination vertex id, maximum length)
        //   <result> = getLineage(vertex id, depth, direction)
        //   <result> = getLineage(vertex id, depth, direction, terminating expression)
        //   <result> = getLineage(<result>, depth, direction)
        //   <result> = getLineage(<result>, depth, direction, terminating expression)
        //   <result> = <result>.getChildren(expression)
        //   <result> = <result>.getParents(expression)

        //   <result>.print(annotations)
        //   export <result> <path>
        //   list
        //   exit

        Pattern vertexPattern = Pattern.compile("([a-zA-Z0-9]+)\\s*=\\s*([a-zA-Z0-9]+\\.)?getVertices\\((.+)\\)[;]?");
        Pattern edgePattern = Pattern.compile("([a-zA-Z0-9]+)\\s*=\\s*([a-zA-Z0-9]+\\.)?getEdges\\((.+)\\)[;]?");
        Pattern pathPattern = Pattern.compile("([a-zA-Z0-9]+)\\s*=\\s*([a-zA-Z0-9]+\\.)?getPaths\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)[;]?");
        Pattern lineagePattern = Pattern.compile("([a-zA-Z0-9]+)\\s*=\\s*([a-zA-Z0-9]+\\.)?getLineage\\(\\s*([a-zA-Z0-9]+)\\s*,\\s*(\\d+)\\s*,\\s*([a-zA-Z]+)\\s*(,\\s*.+)?\\s*\\)[;]?");
        Pattern printPattern = Pattern.compile("([a-zA-Z0-9]+\\.)print\\((.*)\\)[;]?");
        Pattern childrenPattern = Pattern.compile("([a-zA-Z0-9]+)\\s*=\\s*([a-zA-Z0-9]+\\.)getChildren\\((.+)\\)[;]?");
        Pattern parentsPattern = Pattern.compile("([a-zA-Z0-9]+)\\s*=\\s*([a-zA-Z0-9]+\\.)getParents\\((.+)\\)[;]?");

        Matcher vertexMatcher = vertexPattern.matcher(input);
        Matcher edgeMatcher = edgePattern.matcher(input);
        Matcher pathMatcher = pathPattern.matcher(input);
        Matcher lineageMatcher = lineagePattern.matcher(input);
        Matcher printMatcher = printPattern.matcher(input);
        Matcher childrenMatcher = childrenPattern.matcher(input);
        Matcher parentsMatcher = parentsPattern.matcher(input);

        String queryTarget = null, queryString = null, result = null;
        if (vertexMatcher.matches()) {
            result = vertexMatcher.group(1);
            queryTarget = vertexMatcher.group(2);
            String expression = vertexMatcher.group(3);
            queryString = "query " + QUERY_STORAGE + " vertices " + expression;
        } else if (edgeMatcher.matches()) {
            result = edgeMatcher.group(1);
            queryTarget = edgeMatcher.group(2);
            String expression = edgeMatcher.group(3);
            queryString = "query " + QUERY_STORAGE + " edges " + expression;
        } else if (pathMatcher.matches()) {
            result = pathMatcher.group(1);
            queryTarget = pathMatcher.group(2);
            String srcVertex = pathMatcher.group(3);
            String dstVertex = pathMatcher.group(4);
            String maxLength = pathMatcher.group(5);
            queryString = "query " + QUERY_STORAGE + " paths " + srcVertex + " " + dstVertex + " " + maxLength;
        } else if (lineageMatcher.matches()) {
            result = lineageMatcher.group(1);
            queryTarget = lineageMatcher.group(2);
            String vertexId = lineageMatcher.group(3);
            String depth = lineageMatcher.group(4);
            String direction = lineageMatcher.group(5);
            String terminatingExpression = (lineageMatcher.group(6) == null) ? "null" : lineageMatcher.group(6).substring(1).trim();
            queryString = "query " + QUERY_STORAGE + " lineage " + vertexId + " " + depth + " " + direction + " " + terminatingExpression;
            try {
                if ((queryTarget == null) && graphObjects.containsKey(vertexId)) {
                    Graph totalGraphs = new Graph();
                    long begintime = System.currentTimeMillis();
                    for (AbstractVertex vertex : graphObjects.get(vertexId).vertexSet()) {
                        String storageId = vertex.getAnnotation(Settings.getProperty("storage_identifier"));
                        queryString = "query " + QUERY_STORAGE + " lineage " + storageId + " " + depth + " " + direction + " " + terminatingExpression;
                        SPADEQueryIn.println(queryString);
                        String resultString = (String) SPADEQueryOut.readObject();
                        if (resultString.equals("graph")) {
                            Graph resultGraph = (Graph) SPADEQueryOut.readObject();
                            totalGraphs = Graph.union(totalGraphs, resultGraph);
                        } else {
                            outputStream.println(resultString + "\n");
                        }
                    }
                    long endtime = System.currentTimeMillis();
                    long elapsedtime = endtime - begintime;
                    System.out.println("Time taken for query: " + elapsedtime + " ms");

                    graphObjects.put(result, totalGraphs);
                    String queryExpression = input.split("\\s*=\\s*")[1];
                    graphExpressions.put(result, queryExpression);
                    return;
                }
            } catch (IOException | ClassNotFoundException exception) {
                exception.printStackTrace();
            }
        } else if (printMatcher.matches()) {
            queryTarget = printMatcher.group(1);
            queryTarget = queryTarget.substring(0, queryTarget.length() - 1);
            String annotationsArray[] = printMatcher.group(2).split(",");
            for(int i=0; i<annotationsArray.length; i++) {
                annotationsArray[i] = annotationsArray[i].trim();
            }
            Set<String> annotations = new HashSet<>(Arrays.asList(annotationsArray));
            annotations.removeAll(Arrays.asList("", null));
            if (!graphObjects.containsKey(queryTarget)) {
                System.out.println("Error: graph " + queryTarget + " does not exist");
                return;
            }
            Graph target = graphObjects.get(queryTarget);
            System.out.println("Total Vertices" + " : " + target.vertexSet().size() + "\n");
            for (AbstractVertex vertex : target.vertexSet()) {
                for (Map.Entry<String, String> entry : vertex.getAnnotations().entrySet()) {
                    String annotation = entry.getKey();
                    String value = entry.getValue();
                    if (annotations.size()==0 || annotations.contains(annotation)) {
                        System.out.println("\t" + annotation + " : " + value);
                    }
                }
                System.out.println();
            }
            System.out.println("\n\nTotal Edges" + " : " + target.edgeSet().size() + "\n");
            for (AbstractEdge edge : target.edgeSet()) {
                for (Map.Entry<String, String> entry : edge.getAnnotations().entrySet()) {
                    String annotation = entry.getKey();
                    String value = entry.getValue();
                    if (annotations.size()==0 || annotations.contains(annotation)) {
                        System.out.println("\t" + annotation + " : " + value);
                    }
                }
                System.out.println();
            }
            return;
        } else if (childrenMatcher.matches()) {
            result = childrenMatcher.group(1);
            queryTarget = childrenMatcher.group(2);
            queryTarget = queryTarget.substring(0, queryTarget.length() - 1);
            String expression = childrenMatcher.group(3);
            parseQuery(result + " = getLineage(" + queryTarget + ", 1, desc)");
            parseQuery(result + " = " + result + ".getVertices(" + expression + ")");
            return;
        } else if (parentsMatcher.matches()) {
            result = parentsMatcher.group(1);
            queryTarget = parentsMatcher.group(2);
            queryTarget = queryTarget.substring(0, queryTarget.length() - 1);
            String expression = parentsMatcher.group(3);
            parseQuery(result + " = getLineage(" + queryTarget + ", 1, anc)");
            parseQuery(result + " = " + result + ".getVertices(" + expression + ")");
            return;
        }

        try {
            if ((queryTarget == null) && (queryString != null)) {
                long begintime, endtime;
                begintime = System.currentTimeMillis();
                SPADEQueryIn.println(queryString);
                String resultString = (String) SPADEQueryOut.readObject();
                if (resultString.equals("graph")) {
                    Graph resultGraph = (Graph) SPADEQueryOut.readObject();
                    String queryExpression = input.split("\\s*=\\s*")[1];
                    graphObjects.put(result, resultGraph);
                    graphExpressions.put(result, queryExpression);
                } else {
                    outputStream.println(resultString + "\n");
                }
                endtime = System.currentTimeMillis();
                long elapsedtime = endtime - begintime;

                System.out.println("Time taken for query: " + elapsedtime + " ms");
            } else if (queryTarget != null) {
                queryTarget = queryTarget.substring(0, queryTarget.length() - 1);
                if (!graphObjects.containsKey(queryTarget)) {
                    System.out.println("Error: graph " + queryTarget + " does not exist");
                    return;
                }
                Graph resultGraph = null;
                if (vertexMatcher.matches()) {
                    String queryExpression = vertexMatcher.group(3);
                    resultGraph = graphObjects.get(queryTarget).getVertices(queryExpression);
                } else if (pathMatcher.matches()) {
                    int srcVertexId = Integer.parseInt(pathMatcher.group(3));
                    int dstVertexId = Integer.parseInt(pathMatcher.group(4));
                    int maxLength = Integer.parseInt(pathMatcher.group(5));
                    resultGraph = graphObjects.get(queryTarget).getPaths(srcVertexId, dstVertexId, maxLength);
                } else if (lineageMatcher.matches()) {
                    int vertexId = Integer.parseInt(lineageMatcher.group(3));
                    int depth = Integer.parseInt(lineageMatcher.group(4));
                    String direction = lineageMatcher.group(5);
                    String terminatingExpression = (lineageMatcher.group(6) == null) ? "null" : lineageMatcher.group(6).substring(1).trim();
                    resultGraph = graphObjects.get(queryTarget).getLineage(vertexId, depth, direction, terminatingExpression);
                }
                if (resultGraph != null) {
                    graphObjects.put(result, resultGraph);
                    String queryExpression = input.split("\\s*=\\s*")[1];
                    graphExpressions.put(result, queryExpression);
                }
            }
        } catch (IOException | ClassNotFoundException | NumberFormatException exception) {
            exception.printStackTrace();
        }
    }
}
