package com.testapp;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

public interface ObjectsPool<R> extends Closeable {
    void open();

    boolean isOpen();

    R acquire();

    R acquire(long timeout, TimeUnit timeUnit);

    void release(R resource);

    boolean add(R resource);

    boolean remove(R resource);

    void closeImmediately();
}
