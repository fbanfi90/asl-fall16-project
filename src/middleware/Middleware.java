package middleware;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.net.*;
import java.nio.channels.*;
import java.util.logging.Formatter;
import java.util.logging.FileHandler;
import java.util.logging.*;

/**
 * Middleware main entry point (clients handling).
 */
public class Middleware
{
    /* Parameters */

    private String middlewareIP;
    private int middlewarePort;
    private int numOfServers;

    /* Members */

    private ServerReader[] serverReaders;
    private ServerWriter[] serverWriters;

    /* Functions */

    /**
     * Start the middleware in test mode for the hash function.
     *
     * @param fcn   hash function to be tested (e.g. "MD5" or "SHA-1"), empty string "" is Java's hashCode()
     */
    public Middleware(String fcn)
    {
        Helper.testHashFunction(fcn);
    }

    /**
     * Constructor.
     *
     * @param middlewareIP      middleware IP address
     * @param middlewarePort    middleware port
     * @param serverAddresses   list of server address:port pairs
     * @param numOfThreads      number of threads in pools
     * @param replication       replication factor for writes
     */
    public Middleware(String middlewareIP, int middlewarePort, List<String> serverAddresses, int numOfThreads, int replication)
    {
        // Set parameters.
        this.middlewareIP = middlewareIP;
        this.middlewarePort = middlewarePort;
        this.numOfServers = serverAddresses.size();
        String[] serverIPs = new String[numOfServers];
        int[] serverPorts = new int[numOfServers];
        int i = 0;
        for (String address : serverAddresses)
        {
            String[] fields = address.split(":");
            serverIPs[i] = fields[0];
            serverPorts[i] = Integer.parseInt(fields[1]);
            ++i;
        }

        // Start logger.
        new File("log").mkdirs();
        Logger logger = Logger.getLogger(Middleware.class.getName());
        logger.setUseParentHandlers(false);
        try
        {
            FileHandler fileHandler = new FileHandler("log/mw_" + new SimpleDateFormat("yy-MM-dd_HH:mm:ss").format(new Date()) + ".log");
            fileHandler.setFormatter(new Formatter()
            {
                @Override
                public String format(LogRecord record)
                {
                    return record.getMessage() + '\n';
                }
            });
            logger.addHandler(fileHandler);
            logger.info("OP, ID, T_total, T_queue, T_server, success");
        }
        catch (IOException e)
        {
            System.out.println("Middleware: cannot create log file");
            System.exit(-1);
        }

        // Create and start writers and readers.
        serverWriters = new ServerWriter[numOfServers];
        for (i = 0; i < numOfServers; ++i)
        {
            String[] replServerIPs = new String[replication];
            int[] replServerPorts = new int[replication];
            for (int j = 0; j < replication; ++j)
            {
                replServerIPs[j] = serverIPs[Helper.floorMod(i + j, numOfServers)];
                replServerPorts[j] = serverPorts[Helper.floorMod(i + j, numOfServers)];
            }
            serverWriters[i] = new ServerWriter(replication, replServerIPs, replServerPorts, logger);
        }
        serverReaders = new ServerReader[numOfServers];
        for (i = 0; i < numOfServers; ++i)
            serverReaders[i] = new ServerReader(serverIPs[i], serverPorts[i], numOfThreads, logger);
    }

    /**
     * Start the middleware by accepting asynchronous connections with clients.
     */
    public void run()
    {
        Selector selector = null;
        try
        {
            // Create selector and socket channel.
            selector = Selector.open();
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);

            // Create middleware socket for listening to clients.
            ServerSocket middlewareSocket = ssc.socket();
            middlewareSocket.bind(new InetSocketAddress(middlewareIP, middlewarePort));
            ssc.register(selector, SelectionKey.OP_ACCEPT);
        }
        catch (ClosedChannelException e)
        {
            System.out.println("Middleware: channel closed unexpectedly");
            System.exit(-1);
        }
        catch (IOException e)
        {
            System.out.println("Middleware: could not start middleware on " + middlewareIP + ":" + middlewarePort);
            System.exit(-1);
        }

        long setID = 0, getID = 0, delID = 0;
        while (true)
        {
            try
            {
                selector.select();
            }
            catch (IOException e)
            {
                System.out.println("Middleware: could not select event");
                System.exit(-1);
            }

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext())
            {
                SelectionKey selKey = it.next();
                if (selKey.isAcceptable())
                {
                    try
                    {
                        // Accept the new connection from client.
                        SocketChannel sc = ((ServerSocketChannel)selKey.channel()).accept();
                        sc.configureBlocking(false);

                        // Add the new connection to the selector.
                        sc.register(selector, SelectionKey.OP_READ);
                        it.remove();
                    }
                    catch (ClosedChannelException e)
                    {
                        System.out.println("Middleware: channel closed unexpectedly");
                        System.exit(-1);
                    }
                    catch (IOException ex)
                    {
                        System.out.println("Middleware: client unreachable");
                        System.exit(-1);
                    }
                }
                else if (selKey.isReadable())
                {
                    // Create request-socket pair.
                    Request request = new Request();
                    request.tArr = Helper.time();

                    // Read client's request.
                    SocketChannel clientSocket = (SocketChannel)selKey.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(Helper.LARGE_BUFFER_SZ);
                    request.socket = clientSocket;
                    try
                    {
                        // Read client's request.
                        int r = clientSocket.read(buffer);
                        if (r == -1) // No data, happens when a client disconnects.
                        {
                            clientSocket.close();
                            it.remove();
                            continue;
                        }
                        byte[] bytes = new byte[r];
                        buffer.flip();
                        buffer.get(bytes);
                        request.bytes = bytes;

                        // Get hash code from request key and corresponding hash code.
                        byte[] key = new byte[16];
                        System.arraycopy(bytes, 4, key, 0, 16);
                        int hash = new String(key, "ASCII").hashCode();
                        int serverID = Helper.floorMod(hash, numOfServers);

                        // Select writer/reader for this request and forward it.
                        if (bytes[0] == 's') // SET
                        {
                            request.op = "SET";
                            request.id = setID++;
                            serverWriters[serverID].enqueue(request);
                        }
                        else if (bytes[0] == 'g') // GET
                        {
                            request.op = "GET";
                            request.id = getID++;
                            serverReaders[serverID].enqueue(request);
                        }
                        else if (bytes[0] == 'd') // USELESS!
                        {
                            request.isDelete = true;
                            request.op = "DELETE";
                            request.id = delID++;
                            key = new byte[16];
                            System.arraycopy(bytes, 7, key, 0, 16);
                            hash = new String(key).hashCode();
                            serverID = Helper.floorMod(hash, numOfServers);
                            serverWriters[serverID].enqueue(request);
                        }
                        else
                        {
                            System.out.println("Middleware: invalid request");
                            System.exit(-1);
                        }

                        it.remove();
                    }
                    catch (IOException e)
                    {
                        try
                        {
                            System.out.println("Middleware: client " + clientSocket.getRemoteAddress() + " unreachable");
                            System.exit(-1);
                        }
                        catch (IOException ex)
                        {
                            System.out.println("Middleware: client unreachable");
                            System.exit(-1);
                        }
                    }
                }
            }
        }
    }
}