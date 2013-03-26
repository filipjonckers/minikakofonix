/**
 * ShutdownThread.
 * 
 * Copyright (c) 2013 Filip Jonckers.
 * 
 */

public class ShutdownThread extends Thread
{
    private Minikakofonix kakofonix_ = null;

    public ShutdownThread(Minikakofonix kakofonix)
    {
        super();
        kakofonix_ = kakofonix;
    }

    /**
     * Cleanly close the current recording.
     */
    public void run()
    {
        kakofonix_.stop();
    }
}
