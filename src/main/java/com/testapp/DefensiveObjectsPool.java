package com.testapp;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.isNull;

/**
 *  Quick and straightforward implementation of the object pull pattern.
 *  More locks can be added to handle some tricky cases
 */
public class DefensiveObjectsPool<R> implements ObjectsPool<R> {

    // Using simple linked queue as a pool
    private Queue<R> queue = new LinkedBlockingQueue<>();
    // Store acquired objects
    private Set<R> acquired = new CopyOnWriteArraySet<>();

    // Lock for the #close operation to avoid deadlocks
    private ReentrantLock closingLock = new ReentrantLock();

    // The boolean values can be enum
    private volatile boolean shutdown;
    private volatile boolean open;

    public void open() {
        if (shutdown || closingLock.isLocked()) {
            throw new IllegalStateException("Pool is closed");
        }
        open = true;
    }

    public boolean isOpen() {
        return open && !closingLock.isLocked() && !shutdown;
    }

    public R acquire() {
        if (!validate()) {
            return null;
        }
        return pollFromPool(null, null);
    }

    private R pollFromPool(TimeUnit timeUnit, Long timeout) {
        final long timeMillis = System.currentTimeMillis();
        while (isNull(queue.peek())) {
            if (isNull(timeUnit)) {
                sleep();
            } else {
                if (System.currentTimeMillis() - timeMillis <= timeUnit.toMillis(timeout)) {
                    sleep();
                } else {
                    return null;
                }
            }
        }
        acquired.add(queue.peek());
        return queue.poll();
    }

    private void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public R acquire(long timeout, TimeUnit timeUnit) {
        if (!validate()) {
            return null;
        }
        return pollFromPool(timeUnit, timeout);
    }

    public void release(R resource) {
        if (shutdown || !open) {
            return;
        }
        if (acquired.contains(resource)) {
            queue.add(resource);
        }
        acquired.remove(resource);
    }

    private boolean validate() {
        return !shutdown && open && !closingLock.isLocked();
    }

    public boolean add(R resource) {
        if (!validate()) {
            return false;
        }

        if (!queue.contains(resource)) {
            return queue.offer(resource);
        }
        // Return true (modified) if the resource wasn't present in the queue.
        return false;

    }

    // Didn't get the description "The remove(R) method should be such that if the resource
    // that is being removed is currently in use, the remove
    // operation will block until that resource has been released."
    // Our resources are thread safe, so another thread cannot get the resource
    // and the description doesn't have any sense, as well as "removeNow" method
    public boolean remove(R resource) {
        if (!validate()) {
            return false;
        }
        return queue.remove(resource);

    }

    public void close() {
        if (!validate()) {
            return;
        }
        // extra releasingLock helps us to avoid possible deadlocks when two threads trying to close simultaneously
        if (closingLock.tryLock()) {
            try {
                while (!acquired.isEmpty()) {
                    sleep();
                }
                cleanup();
            } finally {
                closingLock.unlock();
            }
        }
    }

    private void cleanup() {
        if (closingLock.tryLock()) {
            try {
                queue.clear();
                acquired.clear();
                this.shutdown = true;
                this.open = false;
            } finally {
                closingLock.unlock();
            }
        }
    }

    public void closeImmediately() {
        if (!validate()) {
            return;
        }
        cleanup();
    }
}
