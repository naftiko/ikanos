/**
 * Copyright 2025-2026 Naftiko
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.naftiko.engine.exposes.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-size circular buffer that retains the most recent completed trace summaries.
 * Thread-safe via a reentrant lock.
 */
public class TraceRingBuffer {

    private final TraceSummary[] buffer;
    private final int capacity;
    private int head;
    private int size;
    private final ReentrantLock lock = new ReentrantLock();

    public TraceRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("TraceRingBuffer capacity must be at least 1, got: " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new TraceSummary[capacity];
        this.head = 0;
        this.size = 0;
    }

    public void add(TraceSummary summary) {
        lock.lock();
        try {
            buffer[head] = summary;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        } finally {
            lock.unlock();
        }
    }

    public List<TraceSummary> getAll() {
        lock.lock();
        try {
            List<TraceSummary> result = new ArrayList<>(size);
            // Return most recent first
            for (int i = 0; i < size; i++) {
                int idx = (head - 1 - i + capacity) % capacity;
                result.add(buffer[idx]);
            }
            return Collections.unmodifiableList(result);
        } finally {
            lock.unlock();
        }
    }

    public TraceSummary findByTraceId(String traceId) {
        lock.lock();
        try {
            for (int i = 0; i < size; i++) {
                int idx = (head - 1 - i + capacity) % capacity;
                if (buffer[idx].traceId().equals(traceId)) {
                    return buffer[idx];
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public int getSize() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public int getCapacity() {
        return capacity;
    }
}
