package com.cloudkingtv.townstead;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one background thread for work that mustn't hold up a server tick: working out town models and the plans of
 * buildings about to level up. What it's given is copied first or never changed after, and what it makes is handed
 * back to the server thread whole.
 */
public final class Worker {
    public static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Townstead worker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private Worker() {}
}
