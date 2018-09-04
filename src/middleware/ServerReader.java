package middleware;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * GET requests handling logic.
 */
class ServerReader
{
    /* Members */

    private BlockingQueue<Request> queue;
    private Logger logger;

    /* Functions */

    /**
     * Constructor.
     *
     * @param serverIP      IP address of server
     * @param serverPort    port of server
     * @param poolSz        size of thread pool
     * @param logger        logger object
     */
    ServerReader(String serverIP, int serverPort, int poolSz, Logger logger)
    {
        // Set parameters.
        this.logger = logger;

        // Start reader thread pool.
        queue = new LinkedBlockingQueue<Request>();
        for (int i = 0; i < poolSz; i++)
            (new Thread(new ReaderThread(serverIP, serverPort))).start();
    }

    /**
     * Add a new request to be processed.
     *
     * @param request   the request-socket pair to be processed
     */
    void enqueue(Request request)
    {
        try
        {
            request.tEnq = Helper.time();
            queue.put(request);
        }
        catch (InterruptedException e)
        {
            System.out.println("ServerReader: queue interrupted by 'put' on " + new String(request.bytes));
            System.exit(-1);
        }
    }

    /**
     * Process a request as soon as available from the queue.
     */
    private class ReaderThread implements Runnable
    {
        private SocketChannel serverSocket;

        ReaderThread(String serverIP, int serverPort)
        {
            // Connect to server.
            try
            {
                serverSocket = SocketChannel.open(new InetSocketAddress(serverIP, serverPort));
            }
            catch (IOException e)
            {
                System.out.println("ServerReader: could not connect to server " + serverIP + ":" + serverPort);
                System.exit(-1);
            }
        }

        public void run()
        {
            try
            {
                while (true)
                {
                    // Dequeue request/socket pair.
                    Request request = queue.take();
                    request.tDeq = Helper.time();

                    // Write request to server.
                    ByteBuffer buffer = ByteBuffer.wrap(request.bytes);
                    serverSocket.write(buffer);
                    request.tSent = Helper.time();

                    // Read response from server.
                    buffer = ByteBuffer.allocate(Helper.LARGE_BUFFER_SZ);
                    serverSocket.read(buffer);
                    request.tRecv = Helper.time();

                    // Successful responses start with 'VALUE', otherwise with 'END'.
                    request.success = buffer.array()[0] == 'V';

                    // Write request to client.
                    buffer.flip();
                    request.socket.write(buffer);
                    request.tLeft = Helper.time();

                    // Log timing data for this request.
                    request.log(logger);
                }
            }
            catch (IOException e)
            {
                System.out.println("ServerReader: client unreachable");
                System.exit(-1);
            }
            catch (InterruptedException e)
            {
                System.out.println("ServerReader: queue interrupted by 'take'");
                System.exit(-1);
            }
        }
    }
}
