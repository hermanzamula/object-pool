package com.testapp;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

public class DefensiveObjectsPoolTest {

    @Test
    public void testCanOpenAndClose() throws IOException {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();
        assertTrue(pool.isOpen());
        pool.close();
        assertFalse(pool.isOpen());
    }

    @Test
    public void testCanNotUseWithoutOpen() {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();

        final Object acquire = pool.acquire();

        assertNull(acquire);
        assertFalse(pool.add(new Object()));
        assertNull(pool.acquire());
        assertNull(pool.acquire(100, TimeUnit.MILLISECONDS));

        pool.open();

        final Object resource = new Object();
        assertTrue(pool.add(resource));
        assertNotNull(pool.acquire());

        pool.release(resource);
        assertNotNull(pool.acquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testAddAndAcquire() {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();

        executeInParallel(() -> {
            final Object resource = new Object();
            randomSleep();
            Assert.assertTrue(pool.add(resource));
        }, 100);

        final Set<Object> uniqueObjects = new CopyOnWriteArraySet<>();

        executeInParallel(() -> {
            randomSleep();
            final Object o = pool.acquire();
            assertTrue(uniqueObjects.add(o));
        }, 100);

        assertThat(uniqueObjects.size(), is(100));
        run(() -> {
            randomSleep();
            pool.release(uniqueObjects.iterator().next());
        });
        assertThat(pool.acquire(), notNullValue());
    }

    @Test
    public void testAddAndAcquireWithTimeUnit() {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();

        executeInParallel(() -> {
            final Object resource = new Object();
            randomSleep();
            Assert.assertTrue(pool.add(resource));
        }, 100);

        final Set<Object> uniqueObjects = new CopyOnWriteArraySet<>();

        executeInParallel(() -> {
            randomSleep();
            final Object o = pool.acquire(100, TimeUnit.MILLISECONDS);
            assertTrue(uniqueObjects.add(o));
        }, 100);

        assertThat(uniqueObjects.size(), is(100));
        assertThat(pool.acquire(100, TimeUnit.MILLISECONDS), nullValue());
    }

    @Test
    public void testAddAndAcquireRandomly() {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();

        final HashSet<Object> addedObjects = new HashSet<>();
        final HashSet<Object> acquiredObjects = new HashSet<>();

        executeInParallel(() -> {
            randomSleep();
            final boolean b = new Random().nextBoolean();
            if (b) {
                final Object resource = new Object();
                if (!pool.add(resource)) {
                    fail();
                }
                addedObjects.add(resource);
            } else {
                final Object acquire = pool.acquire(100, TimeUnit.MILLISECONDS);
                if (acquire != null) acquiredObjects.add(acquire);
                if (acquiredObjects.size() > addedObjects.size()) {
                    fail(acquiredObjects.size() + " " + addedObjects.size());
                }
            }

        }, 1000);

        assertTrue(addedObjects.size() >= acquiredObjects.size());
        assertTrue(addedObjects.containsAll(acquiredObjects));
    }

    @Test
    public void testRemove() {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();

        executeInParallel(() -> {
            try {
                final Object resource = new Object();
                randomSleep();
                if(!pool.add(resource)) {
                    fail("Cannot add");
                }
                randomSleep();
                final boolean remove = pool.remove(resource);
                if(!remove) {
                    fail("Cannot remove");
                }
            } catch (Exception e) {
                fail(e.getMessage());
            }
        }, 100);
    }

    @Test
    public void testCloseOnlyAfterReleasing() throws InterruptedException, IOException {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();

        // Add and then acquire some object
        pool.add(new Object());
        final Object acquired = pool.acquire();

        final Thread closingPoolThread = run(() -> {
            try {
                pool.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        Thread.sleep(100);
        // Check for deadlock
        pool.close();

        //Be sure that pool is is still not closed
        assertTrue(closingPoolThread.isAlive() && !pool.isOpen());

        pool.release(acquired);

        Thread.sleep(1000);
        assertFalse(pool.isOpen());
        assertFalse(closingPoolThread.isAlive());
    }

    @Test
    public void testResourceCanBeAcquiredOnlyOnce() {
        final ObjectsPool<Object> pool = new DefensiveObjectsPool<>();
        pool.open();

        pool.add(pool);

        final LinkedList<Object> list = new LinkedList<>();

        executeInParallel(() -> {
            final Object acquire = pool.acquire(100, TimeUnit.MILLISECONDS);
            if(nonNull(acquire)) {
                list.push(acquire);
            }
        }, 100);

        assertThat(list.size(), is(1));
    }

    private Thread run(Runnable task) {
        final Thread closingPoolThread = new Thread(task);
        closingPoolThread.start();
        return closingPoolThread;
    }

    private void randomSleep() {
        try {
            Thread.sleep(new Random().nextInt(100));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void executeInParallel(Runnable task, int runsCount) {
        final ExecutorService service = newFixedThreadPool(runsCount);
        Stream.iterate(0, (c) -> c++)
                .limit(runsCount)
                .map((c) -> service.submit(task)).collect(Collectors.toList())
                .forEach(f -> {
                    try {
                        f.get();
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    }
                });


    }
}
