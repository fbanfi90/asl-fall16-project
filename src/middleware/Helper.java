package middleware;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.Random;

/**
 * Helper class with testing functions.
 */
class Helper
{
    /* Constants */

    /**
     * Size of the socket buffers by write operations.
     */
    static final int HUGE_BUFFER_SZ = 2097152;

    /**
     * Maximum size of buffers used for all (unknown size) requests and GET responses.
     */
    static final int LARGE_BUFFER_SZ = 2048;

    /**
     * Maximum size of buffers used for SET/DELETE responses.
     *
     * Note: this *MUST* be a multiple of 8! (STORED\r\n)
     */
    static final int SMALL_BUFFER_SZ = 64;

    /**
     * Set time unit for logging.
     *
     * 0: milliseconds
     * 1: microseconds
     * 2: nanoseconds
     */
    static final int TIME_UNIT = 1;

    /* Structures */

    /**
     * Simple structure holding a pair of object of (possibly) different type.
     *
     * @param <T1>  the first object of the pair
     * @param <T2>  the second object of the pair
     */
    static class Pair<T1, T2>
    {
        T1 first;
        T2 second;

        Pair(T1 first, T2 second)
        {
            this.first = first;
            this.second = second;
        }
    }

    /* Functions */

    /**
     * Returns the floor modulus of the {@code int} arguments.
     * (Taken from Java 8 source code.)
     *
     * @param x the dividend
     * @param y the divisor
     * @return the floor modulus {@code x - (floorDiv(x, y) * y)}
     */
    static int floorMod(int x, int y)
    {
        return x - floorDiv(x, y) * y;
    }

    /**
     * Returns the largest (closest to positive infinity).
     * (Taken from Java 8 source code.)
     *
     * @param x the dividend
     * @param y the divisor
     * @return the largest (closest to positive infinity)
     */
    static int floorDiv(int x, int y)
    {
        int r = x / y;
        if ((x ^ y) < 0 && (r * y != x))
            r--;
        return r;
    }

    /**
     * Return current time.
     *
     * @return  the current time as a long
     */
    static long time()
    {
        switch (Helper.TIME_UNIT)
        {
            case 0: return System.currentTimeMillis();
            case 1: return System.nanoTime() / 1000;
            case 2: return System.nanoTime();
            default: return 0;
        }
    }

    /* Testing and debugging */

    /**
     * Convert a byte array to a printable hex string.
     *
     * @param byteArray a byte array
     * @return a hex string representation of the byte array
     */
    static String byteArrayToHexString(byte[] byteArray)
    {
        StringBuilder sb = new StringBuilder(byteArray.length * 5);
        for (byte b : byteArray)
            sb.append(String.format("0x%02x ", b & 0xff));
        return sb.toString();
    }

    /**
     * Heuristic distribution test for hash function using n servers.
     * It shows that Java's hashCode behaves sufficiently uniform-random like.
     *
     * @param n     number of servers
     * @param tot   total number of samples
     * @param fcn   hash function to be tested (e.g. "MD5" or "SHA-1"), empty string "" is Java's hashCode()
     */
    static double[] getHashDistribution(int n, int tot, String fcn)
    {
        int[] counts = new int[n];

        for (int i = 0; i < tot; ++i)
        {
            int hash = 0;
            String randStr = new String(new BigInteger(128, new Random()).toByteArray());

            if (fcn.equals(""))
            {
                hash = floorMod(randStr.hashCode(), n);
            }
            else
            {
                byte hashBytes[];
                try
                {
                    MessageDigest md = MessageDigest.getInstance(fcn);
                    md.update(randStr.getBytes());
                    hashBytes = md.digest();
                    hash = floorMod(hashBytes[0], n);
                }
                catch (NoSuchAlgorithmException e) { }
            }

            ++counts[hash];

            //for (int c : counts)
            //    System.out.print(new DecimalFormat("#0.00").format(c / (double)(i + 1) * 100) + "% ");
            //System.out.print('\r');
        }

        double[] dist = new double[n];
        for (int i = 0; i < n; ++i)
            dist[i] = counts[i] / (double)tot;
        return dist;
    }

    /**
     * Compute the statistical distance (total variation distance) of two (empirical) distributions
     * as the 1-norm of the difference of the two probability distributions.
     *
     * @param X the first (empirical) distribution
     * @param Y the second (empirical) distribution
     * @return the statistical distance between X and Y
     */
    static double statisticalDistance(double[] X, double[] Y)
    {
        double sd = 0;
        for (int i = 0; i < X.length; ++i)
            sd += Math.abs(X[i] - Y[i]);
        return sd / 2;
    }

    /**
     * Print statistical distance of hash function from uniform distribution for n = 1 .. 7.
     *
     * @param fcn   hash function to be tested (e.g. "MD5" or "SHA-1"), empty string "" is Java's hashCode()
     */
    static void testHashFunction(String fcn)
    {
        for (int n = 2; n <= 7; ++n)
        {
            double[] X = getHashDistribution(n, 10000000, fcn);
            double[] U = new double[n];
            for (int i = 0; i < n; ++i)
                U[i] = 1 / (double)n;
            System.out.println(n + " " + new DecimalFormat("#0.000000").format(statisticalDistance(X, U)));
        }
    }
}
