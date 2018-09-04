package middleware;

import java.nio.channels.SocketChannel;
import java.util.logging.Logger;

/**
 * Helper class for storing a request with the associated client socket and some timing info.
 */
class Request
{
    /* Pair elements */

    byte[] bytes;
    SocketChannel socket;

    /* Logging parameters */

    String op;
    long id;
    long tArr;
    long tEnq;
    long tDeq;
    long tSent;
    long tRecv;
    long tLeft;
    boolean success;

    /* Other */

    boolean isDelete = false;

    /* Functions */

    /**
     * Log timing info for this request.
     *
     * @param logger    a Logger object
     */
    void log(Logger logger)
    {
        if (id % 100 == 0)
            logger.info(String.format("%s %d %d %d %d %b",
                    op,
                    id,
                    tLeft - tArr,
                    tDeq - tEnq,
                    tRecv - tSent,
                    success));
    }

    /**
     * Log timing info for this request (plus exact times).
     *
     * @param logger    a Logger object
     */
    void log2(Logger logger)
    {
        if (id % 100 == 0)
            logger.info(String.format("%s %d %d %d %d %b %d %d %d %d %d %d",
                    op,
                    id,
                    tLeft - tArr,
                    tDeq - tEnq,
                    tRecv - tSent,
                    success,
                    tArr,
                    tEnq,
                    tDeq,
                    tSent,
                    tRecv,
                    tLeft));
    }
}