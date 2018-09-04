package middleware;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * SET requests handling logic.
 */
class ServerWriter
{
    /* Parameters */

    private int replication;

    /* Members */

    private HashMap<SocketChannel, Queue<Helper.Pair<Boolean, Long>>> hashMap;
    private Queue<Request> sentRequests;
    private SocketChannel[] serverSockets;
    private Selector selector;
    private BlockingQueue<Request> queue;
    private Logger logger;

    /* Functions */

    /**
     * Constructor.
     *
     * @param serverIPs     list of IPs of replication servers
     * @param serverPorts   list of ports of replication servers
     * @param replication   replication factor
     * @param logger        logger object
     */
    ServerWriter(int replication, String[] serverIPs, int[] serverPorts, Logger logger)
    {
        // Set parameters.
        this.replication = replication;
        this.logger = logger;

        // Connect to all replication servers.
        try
        {
            selector = Selector.open();
        }
        catch (IOException e)
        {
            System.out.println("ServerWriter: could not open selector");
            e.printStackTrace();
            System.exit(-1);
        }
        serverSockets = new SocketChannel[replication];
        for (int i = 0; i < replication; ++i)
        {
            try
            {
                serverSockets[i] = SocketChannel.open(new InetSocketAddress(serverIPs[i], serverPorts[i]));
                serverSockets[i].configureBlocking(false);
                serverSockets[i].register(selector, SelectionKey.OP_READ);
                serverSockets[i].setOption(StandardSocketOptions.SO_RCVBUF, Helper.HUGE_BUFFER_SZ);
                serverSockets[i].setOption(StandardSocketOptions.SO_SNDBUF, Helper.HUGE_BUFFER_SZ);
            }
            catch (IOException e)
            {
                System.out.println("ServerWriter: could not connect to server " + serverIPs[i] + ":" + serverPorts[i]);
                e.printStackTrace();
                System.exit(-1);
            }
        }
        queue = new LinkedBlockingQueue<Request>();

        // Initialize queues.
        sentRequests = new LinkedList<Request>();
        hashMap = new HashMap<SocketChannel, Queue<Helper.Pair<Boolean, Long>>>();
        for (SocketChannel sc : serverSockets)
            hashMap.put(sc, new LinkedList<Helper.Pair<Boolean, Long>>());

        // Start writer thread.
        (new Thread(new WriterThread())).start();
    }

    /**
     * Add a new request to be processed.
     *
     * @param request   the request-socket pair to be processed
     */
    void enqueue(Request request)
    {
        request.tEnq = Helper.time();
        queue.offer(request);
    }

    /**
     * Process a request as soon as available from the queue.
     */
    private class WriterThread implements Runnable
    {
        public void run()
        {
            try
            {
                while (true)
                {
                    // Dequeue request/socket pair.
                    Request request = queue.poll(100, TimeUnit.NANOSECONDS);
                    if (request != null)
                    {
                        request.tDeq = Helper.time();

                        // Write request to all replication servers.
                        for (int i = 0; i < replication; ++i)
                        {
                            ByteBuffer buffer = ByteBuffer.wrap(request.bytes);
                            serverSockets[i].write(buffer);
                            if (i == 0)
                                request.tSent = Helper.time();
                        }
                        sentRequests.add(request);
                    }

                    // Asynchronously get responses from servers, if any.
                    selector.selectNow();
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    if (it.hasNext())
                    {
                        while (it.hasNext())
                        {
                            SelectionKey selKey = it.next();
                            if (selKey.isReadable())
                            {
                                // Read server's response.
                                SocketChannel serverSocket = (SocketChannel)selKey.channel();
                                try
                                {
                                    // Read response from server..
                                    ByteBuffer buffer = ByteBuffer.allocate(Helper.SMALL_BUFFER_SZ);
                                    serverSocket.read(buffer);
                                    long tRecv = Helper.time();

                                    // Put response(s) in corresponding queue (may get multiple responses in the buffer!).
                                    String responses[] = new String(buffer.array()).trim().split("\r\n");
                                    for (String response : responses)
                                        hashMap.get(serverSocket).add(new Helper.Pair<Boolean, Long>(response.equals("STORED") || response.equals("DELETED"), tRecv));
                                    it.remove();
                                }
                                catch (IOException e)
                                {
                                    try
                                    {
                                        System.out.println("ServerWriter: server " + serverSocket.getRemoteAddress() + " unreachable");
                                        e.printStackTrace();
                                        System.exit(-1);
                                    }
                                    catch (IOException ex)
                                    {
                                        System.out.println("ServerWriter: server unreachable");
                                        e.printStackTrace();
                                        System.exit(-1);
                                    }
                                }
                            }
                        }

                        // Process responses as long as all queues are non-empty.
                        while (true)
                        {
                            boolean allNonEmpty = true;
                            for (SocketChannel sc : serverSockets)
                                allNonEmpty &= !hashMap.get(sc).isEmpty();
                            if (!allNonEmpty)
                                break;

                            Request topRequest = sentRequests.remove();
                            boolean allOK = true;
                            topRequest.tRecv = 0;
                            for (SocketChannel sc : serverSockets)
                            {
                                Helper.Pair<Boolean, Long> pair = hashMap.get(sc).remove();
                                allOK &= pair.first;
                                topRequest.tRecv = Math.max(topRequest.tRecv, pair.second);
                            }

                            // Forward STORED/DELETED to client only if all responses were STORED/DELETED.
                            if (!topRequest.isDelete)
                                topRequest.socket.write(ByteBuffer.wrap((allOK ? "STORED\n" : "ERROR\n").getBytes()));
                            else
                                topRequest.socket.write(ByteBuffer.wrap((allOK ? "DELETED\n" : "NOT_FOUND\n").getBytes()));
                            topRequest.tLeft = Helper.time();
                            topRequest.success = allOK;

                            // Log timing data for this request.
                            topRequest.log(logger);
                        }
                    }
                }
            }
            catch (IOException e)
            {
                System.out.println("ServerWriter: client unreachable");
                e.printStackTrace();
                System.exit(-1);
            }
            catch (InterruptedException e)
            {
                System.out.println("ServerWriter: queue interrupted by 'take'");
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }
}