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
package io.ikanos.client.grpc;

import java.util.Map;
import com.google.protobuf.AnyProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DurationProto;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.FieldMaskProto;
import com.google.protobuf.StructProto;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.WrappersProto;

/**
 * The protobuf well-known types a descriptor set may import without carrying them, when it was
 * built without {@code --include_imports}.
 */
final class WellKnownTypes {

    private static final Map<String, FileDescriptor> FILES = Map.of(
            "google/protobuf/any.proto", AnyProto.getDescriptor(),
            "google/protobuf/duration.proto", DurationProto.getDescriptor(),
            "google/protobuf/empty.proto", EmptyProto.getDescriptor(),
            "google/protobuf/field_mask.proto", FieldMaskProto.getDescriptor(),
            "google/protobuf/struct.proto", StructProto.getDescriptor(),
            "google/protobuf/timestamp.proto", TimestampProto.getDescriptor(),
            "google/protobuf/wrappers.proto", WrappersProto.getDescriptor());

    private WellKnownTypes() {
    }

    /** @return the well-known file of that name, or {@code null} */
    static FileDescriptor file(String name) {
        return FILES.get(name);
    }
}
