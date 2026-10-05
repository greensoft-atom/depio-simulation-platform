package com.jredis.server.core;

import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.concurrent.locks.LockSupport;

/**
 * The only way into the command thread: many producers (Netty I/O threads, the persistence writer,
 * admin calls), one consumer. Parking uses a sleeping flag set before the final emptiness check,
 * so a wake-up can never be lost.
 */
public final class EventQueue {

    private final MpscUnboundedArrayQueue<Object> queue = new MpscUnboundedArrayQueue<>(1024);
    private volatile Thread consumer;
    private volatile boolean sleeping;

    public void consumer(Thread t) {
        consumer = t;
    }

    public void offer(Object event) {
        queue.offer(event);
        if (sleeping) {
            Thread t = consumer;
            if (t != null) {
                LockSupport.unpark(t);
            }
        }
    }

    public Object poll() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Consumer only: park until an event arrives or the timeout passes. */
    public void park(long timeoutNanos) {
        sleeping = true;
        try {
            if (queue.isEmpty() && timeoutNanos > 0) {
                LockSupport.parkNanos(this, timeoutNanos);
            }
        } finally {
            sleeping = false;
        }
    }
}
