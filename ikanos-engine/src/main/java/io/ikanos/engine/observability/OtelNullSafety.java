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
package io.ikanos.engine.observability;

import io.opentelemetry.api.common.AttributeKey;
import javax.annotation.Nonnull;

/**
 * Null-safety bridge for OpenTelemetry SDK interop.
 *
 * <p>OTel SDK factory methods (e.g. {@link AttributeKey#stringKey(String)},
 * {@code Attributes.of(...)}, {@code Context.current()}) and singletons such as
 * {@code RestletHeaderGetter.INSTANCE} are guaranteed non-null by the SDK contract,
 * but their public signatures do not carry {@code @Nonnull} annotations. As a result,
 * static null analyzers (JDT, SpotBugs) flag every call site as a potential null
 * reference.</p>
 *
 * <p>Rather than scattering {@code @SuppressWarnings("null")} or {@code Objects.requireNonNull}
 * across every call site, this class concentrates the suppression in one place: each helper
 * is annotated {@code @Nonnull} and forwards the SDK value unchanged. Call sites then read as
 * intent-revealing names instead of null-wrapping ceremony, and a future SDK change that
 * actually returns null fails in exactly one well-documented location.</p>
 *
 * <p>This is a pure compile-time bridge — there are no runtime null checks here. If the SDK
 * contract is ever broken, an NPE will surface naturally at the first use site, not here.</p>
 */
public final class OtelNullSafety {

    private OtelNullSafety() {
        // Utility class — no instances.
    }

    /**
     * Returns {@code AttributeKey.stringKey(name)} typed as {@code @Nonnull}.
     */
    @Nonnull
    @SuppressWarnings("null")
    public static AttributeKey<String> stringKey(@Nonnull String name) {
        return AttributeKey.stringKey(name);
    }

    /**
     * Returns {@code AttributeKey.longKey(name)} typed as {@code @Nonnull}.
     */
    @Nonnull
    @SuppressWarnings("null")
    public static AttributeKey<Long> longKey(@Nonnull String name) {
        return AttributeKey.longKey(name);
    }

    /**
     * Identity helper that re-types a string {@link AttributeKey} constant as {@code @Nonnull}.
     * Used to satisfy the null analyzer when passing public {@code ATTR_*} constants whose
     * declared type comes from the unannotated SDK.
     */
    @Nonnull
    @SuppressWarnings("null")
    public static AttributeKey<String> nonNullStringKey(AttributeKey<String> key) {
        return key;
    }

    /**
     * Identity helper that re-types a long {@link AttributeKey} constant as {@code @Nonnull}.
     */
    @Nonnull
    @SuppressWarnings("null")
    public static AttributeKey<Long> nonNullLongKey(AttributeKey<Long> key) {
        return key;
    }

    /**
     * Identity helper that re-types an SDK-supplied reference as {@code @Nonnull}.
     * Use this for {@code Context.current()}, {@code RestletHeaderGetter.INSTANCE},
     * {@code RestletHeaderSetter.INSTANCE}, and similar SDK-guaranteed values.
     */
    @Nonnull
    @SuppressWarnings("null")
    public static <T> T nonNull(T value) {
        return value;
    }
}
