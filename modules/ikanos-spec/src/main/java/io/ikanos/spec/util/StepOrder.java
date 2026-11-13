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
package io.ikanos.spec.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Guards the order of a {@code steps:} map.
 *
 * <p>Until {@code steps:} becomes an ordered list, the document order of the map is the order in
 * which the steps run. JSON and YAML do not guarantee key order, so the order is kept by the
 * loader (Jackson fills a {@code LinkedHashMap}) and checked where a map enters a spec object: a
 * map without a defined iteration order, such as a {@code HashMap} built by an embedder, is
 * rejected instead of running its steps in hash order.</p>
 *
 * <p>Step containers hold their steps in {@link #newSteps()}, a synchronized map that keeps a
 * defined order, so the map one container's getter returns passes the check of another's
 * setter.</p>
 */
public final class StepOrder {

    private StepOrder() {
    }

    /**
     * A new, empty step map: ordered like a {@link LinkedHashMap}, and synchronized on itself like
     * {@link java.util.Collections#synchronizedMap}, whose views must likewise be iterated while
     * holding the map's lock.
     */
    public static <V> Map<String, V> newSteps() {
        return new SynchronizedSteps<>();
    }

    /** A {@link LinkedHashMap}, and so a {@link SequencedMap}, whose operations lock on itself. */
    static final class SynchronizedSteps<V> extends LinkedHashMap<String, V> {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized int size() {
            return super.size();
        }

        @Override
        public synchronized boolean isEmpty() {
            return super.isEmpty();
        }

        @Override
        public synchronized boolean containsKey(Object key) {
            return super.containsKey(key);
        }

        @Override
        public synchronized boolean containsValue(Object value) {
            return super.containsValue(value);
        }

        @Override
        public synchronized V get(Object key) {
            return super.get(key);
        }

        @Override
        public synchronized V getOrDefault(Object key, V defaultValue) {
            return super.getOrDefault(key, defaultValue);
        }

        @Override
        public synchronized V put(String key, V value) {
            return super.put(key, value);
        }

        @Override
        public synchronized void putAll(Map<? extends String, ? extends V> m) {
            super.putAll(m);
        }

        @Override
        public synchronized V putIfAbsent(String key, V value) {
            return super.putIfAbsent(key, value);
        }

        @Override
        public synchronized V remove(Object key) {
            return super.remove(key);
        }

        @Override
        public synchronized boolean remove(Object key, Object value) {
            return super.remove(key, value);
        }

        @Override
        public synchronized V replace(String key, V value) {
            return super.replace(key, value);
        }

        @Override
        public synchronized boolean replace(String key, V oldValue, V newValue) {
            return super.replace(key, oldValue, newValue);
        }

        @Override
        public synchronized void replaceAll(
                java.util.function.BiFunction<? super String, ? super V, ? extends V> function) {
            super.replaceAll(function);
        }

        @Override
        public synchronized V computeIfAbsent(String key,
                java.util.function.Function<? super String, ? extends V> mapping) {
            return super.computeIfAbsent(key, mapping);
        }

        @Override
        public synchronized V computeIfPresent(String key,
                java.util.function.BiFunction<? super String, ? super V, ? extends V> remapping) {
            return super.computeIfPresent(key, remapping);
        }

        @Override
        public synchronized V compute(String key,
                java.util.function.BiFunction<? super String, ? super V, ? extends V> remapping) {
            return super.compute(key, remapping);
        }

        @Override
        public synchronized V merge(String key, V value,
                java.util.function.BiFunction<? super V, ? super V, ? extends V> remapping) {
            return super.merge(key, value, remapping);
        }

        @Override
        public synchronized void clear() {
            super.clear();
        }

        @Override
        public synchronized void forEach(
                java.util.function.BiConsumer<? super String, ? super V> action) {
            super.forEach(action);
        }

        @Override
        public synchronized V putFirst(String key, V value) {
            return super.putFirst(key, value);
        }

        @Override
        public synchronized V putLast(String key, V value) {
            return super.putLast(key, value);
        }

        @Override
        public synchronized boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public synchronized int hashCode() {
            return super.hashCode();
        }

        @Override
        public synchronized String toString() {
            return super.toString();
        }
    }

    /**
     * Returns {@code steps} when its iteration order is defined, or {@code null} when it is
     * {@code null}.
     *
     * @throws IllegalArgumentException when {@code steps} is not a {@link SequencedMap}
     */
    public static <V> Map<String, V> requireOrdered(Map<String, V> steps) {
        if (steps != null && !(steps instanceof SequencedMap)) {
            throw new IllegalArgumentException("Steps run in the order they are declared, so they "
                    + "need a map with a defined order (such as a LinkedHashMap), not a "
                    + steps.getClass().getName());
        }
        return steps;
    }
}
