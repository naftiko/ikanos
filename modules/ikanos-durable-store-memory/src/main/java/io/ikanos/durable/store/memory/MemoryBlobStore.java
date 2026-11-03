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
package io.ikanos.durable.store.memory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.BlobRef;
import io.ikanos.durable.store.BlobStore;

/** Blob store in process memory. References are {@code <execution-id>/<uuid>}. */
final class MemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

    @Override
    public BlobRef put(ExecutionId executionId, byte[] payload) {
        BlobRef ref = new BlobRef(executionId.value() + "/" + UUID.randomUUID());
        blobs.put(ref.value(), payload.clone());
        return ref;
    }

    @Override
    public byte[] get(BlobRef ref) {
        byte[] payload = blobs.get(ref.value());
        if (payload == null) {
            throw new IllegalArgumentException("No blob " + ref.value());
        }
        return payload.clone();
    }

    @Override
    public void deleteAll(ExecutionId executionId) {
        blobs.keySet().removeIf(key -> key.startsWith(executionId.value() + "/"));
    }
}
