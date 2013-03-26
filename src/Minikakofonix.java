import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.TimeZone;

/**
 * MiniKakofonix.
 * 
 * Single Asterix Multicast UDP stream recorder.
 * 
 * Copyright (c) 2013 Filip Jonckers.
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Filip Jonckers
 * @version 1.02
 */

public class Minikakofonix
{
    private final static String VERSION        = "1.02";
    private final static String COPYRIGHT      = "Copyright (c) 2013 Filip Jonckers. (GPL)";
    private final static int    MAXBUFFER      = 8192;

    private OutputStream        astfile_       = null;

    final SimpleDateFormat      sdfStart_      = new SimpleDateFormat("yyyyMMdd_HHmm");
    final SimpleDateFormat      sdfStop_       = new SimpleDateFormat("HHmm");
    final SimpleDateFormat      sdfDebug_      = new SimpleDateFormat("HH:mm:ss.SSSS");

    private String              intf_          = null;
    private String              mcast_         = null;
    private String              astprefix_     = "rec";
    private int                 blocktime_     = 60;
    private int                 blockmsec_     = 60 * 1000;
    private int                 mcastport_     = 0;
    private boolean             verbose_       = false;
    private boolean             veryverbose_   = false;
    private String              recStartLabel_ = "";
    private long                recStartTime_  = 0L;
    private long                recEndTime_    = 0L;

    /**
     * constructor.
     */
    public Minikakofonix(final String[] args)
    {
        System.out.println("MiniKakofonix v" + VERSION);
        System.out.println(COPYRIGHT);

        // parse command line arguments
        if (!parseArgs(args))
            return;

        // minimum requirements
        if(mcast_ == null || mcastport_ == 0)
        {
            System.err.println("Error: multicast group address and udp port number are required.");
            System.exit(-1);
        }
        
        // shutdown hook - catches CTRL-C
        activateShutdownHook();

        // try to create a socket and join the multicast group
        MulticastSocket socket = null;
        try
        {
            System.out.println("Joining multicast group: " + mcast_);
            socket = new MulticastSocket(mcastport_);
            if(intf_ != null)
            {
                socket.setInterface(InetAddress.getByName(intf_));
            }
            InetAddress group = InetAddress.getByName(mcast_);
            socket.joinGroup(group);
        }
        catch (SocketException e)
        {
            System.err.println("Error: Unable to join multicast group: " + mcast_);
            System.exit(-1);
        }
        catch(IOException e)
        {
            System.err.println("Error: Unable to join multicast group: " + mcast_);
            System.exit(-1);            
        }

        // record timer
        sdfStart_.setTimeZone(TimeZone.getTimeZone("UTC"));
        sdfStop_.setTimeZone(TimeZone.getTimeZone("UTC"));
        sdfDebug_.setTimeZone(TimeZone.getTimeZone("UTC"));
        long rxtime = 0;
        resetTimers();

        // create the recording file
        createRecordingFile();

        // main multicast receive loop
        boolean running = true;
        byte[] buffer = new byte[MAXBUFFER];
        while (running)
        {
            DatagramPacket rx = new DatagramPacket(buffer, MAXBUFFER);
            rxtime = System.currentTimeMillis();
            try
            {
                socket.receive(rx);
            }
            catch (IOException e)
            {
                System.err.println("Error: listening to multicast group: " + socket.getInetAddress());
                running = false;
            }

            // check if we need to start a new recording file
            if (rxtime > recEndTime_)
            {
                // close current recording file, rename to timestamp version and open new recording file
                CloseRecordingFile(true);
                resetTimers();
            }

            // write asterix data to recording file
            try
            {
                astfile_.write(buffer, 0, rx.getLength());
            }
            catch (IOException e)
            {
                System.err.println("Error writing to asterix file.");
                System.exit(-1);
            }

            // dump UDP frame information
            if (veryverbose_)
            {
                int size = rx.getLength();
                int dumplen = size > 20 ? 20 : size;
                System.out.println(String.format("RX:%s:%s:%d:%4d:%s", sdfDebug_.format(new Date(rxtime)), mcast_, mcastport_, size, hexDump(buffer, 0, dumplen)));
            }

        }
    }

    /**
     * Create asterix recording file.
     */
    private final void createRecordingFile()
    {
        try
        {
            astfile_ = new BufferedOutputStream(new FileOutputStream(astprefix_ + ".ast"));
        }
        catch (IOException e)
        {
            System.err.println("Error creating asterix file.");
            System.exit(-1);
        }
    }

    /**
     * Close current asterix recording file, rename with timestamp and optionally create new recording file.
     * 
     * @param createNewFile
     *            true if a new recording file shall be created.
     */
    private final void CloseRecordingFile(final boolean createNewFile)
    {
        if (astfile_ != null)
        {
            try
            {
                astfile_.close();
            }
            catch (IOException e)
            {
                System.err.println("Error closing asterix file.");
                System.exit(-1);
            }
            // rename recording file to timestamped file name.
            renameRecordingFile();
        }
        // do we need to create a new recordingfile ?
        if (createNewFile)
            createRecordingFile();
    }

    /**
     * Rename default recording file with timestamp.
     */
    private final void renameRecordingFile()
    {
        final String srcname = astprefix_ + ".ast";
        final String dstname = astprefix_ + "_" + recStartLabel_ + "_" + sdfStop_.format(new Date()) + ".ast";
        if (verbose_)
            System.out.println("Saving Asterix data to: " + dstname);

        File src = new File(srcname);
        File dst = new File(dstname);
        if (dst.exists())
        {
            System.err.println("Error: asterix recording file already exists: " + dstname);
        }
        else
        {
            if (!src.renameTo(dst))
            {
                System.err.println("Error: unable to rename " + srcname + " to " + dstname);
            }
        }
    }

    /**
     * Reset recording timers.
     */
    private final void resetTimers()
    {
        // get timestamp now
        final Date currentTime = new Date();
        // recording file contains timestamp - first part
        recStartLabel_ = sdfStart_.format(currentTime);
        // start of this recording in UTC after 1970/01/01
        recStartTime_ = currentTime.getTime();
        // predict end time in UTC (60 min blocktime = new recording every hour on the hour)
        recEndTime_ = recStartTime_ - (recStartTime_ % blockmsec_) + blockmsec_;
    }

    /**
     * HexDump packet.
     * 
     * @param bytes
     * @param offset
     * @param width
     * @return
     */
    private static String hexDump(byte[] bytes, int offset, int width)
    {
        StringBuilder s = new StringBuilder();
        for (int index = 0; index < width; index++)
        {
            if (index + offset < bytes.length)
            {
                s.append(String.format("%02x ", bytes[index + offset]));
            }
        }
        return s.toString();
    }

    /**
     * Parse command line arguments.
     * 
     * @param args
     *            String array containing the command line arguments.
     * @return true if argument parsing successful and program can continue.
     */
    private final boolean parseArgs(final String[] args)
    {
        // check if no command line arguments given
        if (args.length == 0)
        {
            usage();
            return false;
        }

        // parse command line arguments
        int argcount = 0;
        while (argcount < args.length)
        {
            if ("-h".equals(args[argcount]))
            {
                usage();
                return false;
            }
            if ("-l".equals(args[argcount]))
            {
                listInterfaces();
                return false;
            }
            if ("-v".equals(args[argcount]))
            {
                argcount++;
                verbose_ = true;
                continue;
            }
            if ("-vv".equals(args[argcount]))
            {
                argcount++;
                verbose_ = true;
                veryverbose_ = true;
                continue;
            }
            if ("-i".equals(args[argcount]))
            {
                argcount++;
                intf_ = args[argcount++];
                continue;
            }
            if ("-w".equals(args[argcount]))
            {
                argcount++;
                astprefix_ = args[argcount++];
                continue;
            }
            if ("-b".equals(args[argcount]))
            {
                argcount++;
                try
                {
                    blocktime_ = Integer.parseInt(args[argcount++]);
                    blockmsec_ = blocktime_ * 60 * 1000;
                }
                catch (NumberFormatException e)
                {
                    System.err.println("Error parsing -b argument.");
                    System.exit(-1);
                }
                continue;
            }
            if ("-m".equals(args[argcount]))
            {
                argcount++;
                mcast_ = args[argcount++];
                continue;
            }
            if ("-p".equals(args[argcount]))
            {
                argcount++;
                try
                {
                    mcastport_ = Integer.parseInt(args[argcount++]);
                }
                catch (NumberFormatException e)
                {
                    System.err.println("Error parsing -p argument.");
                    System.exit(-1);
                }
                continue;
            }
            System.err.println("Unknown argument: " + args[argcount++]);
        }

        if (verbose_)
        {
            System.out.println("Settings used:");
            System.out.println("- interface        = " + (intf_ == null ? "(default)" : intf_));
            System.out.println("- file prefix      = " + astprefix_);
            System.out.println("- block time (min) = " + blocktime_);
            System.out.println("- multicast group  = " + mcast_);
            System.out.println("- muticast port    = " + mcastport_);
        }
        return true;
    }

    /**
     * Show available interfaces.
     */
    private final static void listInterfaces()
    {
        System.out.println("Available network interfaces:");
        Enumeration<NetworkInterface> e;
        try
        {
            e = NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements())
            {
                NetworkInterface iface = (NetworkInterface) e.nextElement();
                String s = String.format("- %-5s %-6s %-10s %s", iface.getName(), (iface.isUp() ? "[UP]" : "[DOWN]"), (iface.supportsMulticast() ? "[MCAST]" : "[NO MCAST]"),
                                         (iface.isLoopback() ? " [loopback]" : ""));
                System.out.println(s);
            }
        }
        catch (SocketException e1)
        {
            System.err.println("Error: unable to find network interfaces.");
        }
    }

    /**
     * Shutdown gracefully, stop all listener threads and close recording file.
     */
    public void stop()
    {
        System.out.println("Stopping Asterix recording ...");
        // close current recording file and stop thread
        CloseRecordingFile(false);
    }

    /**
     * Activate the shutdown hook which catches SIGHUP and CTRL-C events.
     */
    private final void activateShutdownHook()
    {
        try
        {
            Runtime.getRuntime().addShutdownHook(new ShutdownThread(this));
        }
        catch (Throwable t)
        {
            System.err.println("Error: Could not add Shutdown hook");
            System.exit(-1);
        }
    }

    /**
     * command line options usage.
     */
    public final void usage()
    {
        System.out.println("Command line arguments:");
        System.out.println("-h          display command line arguments help");
        System.out.println("-v          be verbose (show info about received multicast frames");
        System.out.println("-vv         be very verbose (also display first 20 octets");
        System.out.println("-i <int>    use network interface <int>");
        System.out.println("-w <file>   write asterix raw data to <file>.ast");
        System.out.println("-b <min>    create a new asterix file every <min> minutes");
        System.out.println("-m <mcast>  subscribe to multicast group address <mcast>");
        System.out.println("-p <port>   listen to UDP multicast port <port>");
        System.out.println("Example: minikakofonix -i eth0 -w rec -b 60 -m 239.64.64.1 -p 7150");
    }

    /**
     * Main Thread.
     * 
     * @param args
     */
    public static void main(String[] args)
    {
        // test/debug with Sensis Multilateration UDP - big fragmented frames.
        // args = new String[] { "-v", "-i", "en0", "-m", "239.65.0.254", "-p", "51040", "-w", "test", "-b", "10" };
        new Minikakofonix(args);
    }
}
