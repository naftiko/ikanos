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
package io.naftiko.spec.exposes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Scripting governance controls exposed via the Control Port.
 *
 * <p>Configures defaults, limits, and runtime toggles for script steps. When present on the
 * control adapter, this spec overrides the {@code NAFTIKO_SCRIPTING} environment variable and
 * provides default {@code location} and {@code language} values for script steps that omit
 * them.</p>
 */
public class ScriptingManagementSpec {

    @JsonProperty("enabled")
    private volatile boolean enabled;

    @JsonProperty("defaultLocation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String defaultLocation;

    @JsonProperty("defaultLanguage")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String defaultLanguage;

    @JsonProperty("timeout")
    private volatile int timeout = 5000;

    @JsonProperty("statementLimit")
    private volatile long statementLimit = 100_000;

    @JsonProperty("allowedLanguages")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> allowedLanguages;

    // ── Execution stats (runtime-only, not deserialized from YAML) ──

    @JsonIgnore
    private final AtomicLong totalExecutions = new AtomicLong();

    @JsonIgnore
    private final AtomicLong totalErrors = new AtomicLong();

    @JsonIgnore
    private final AtomicLong totalDurationNanos = new AtomicLong();

    @JsonIgnore
    private volatile String lastExecutionAt;

    public ScriptingManagementSpec() {
        this.allowedLanguages = new CopyOnWriteArrayList<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultLocation() {
        return defaultLocation;
    }

    public void setDefaultLocation(String defaultLocation) {
        this.defaultLocation = defaultLocation;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public long getStatementLimit() {
        return statementLimit;
    }

    public void setStatementLimit(long statementLimit) {
        this.statementLimit = statementLimit;
    }

    public List<String> getAllowedLanguages() {
        return allowedLanguages;
    }

    // ── Execution stats accessors ──

    public long getTotalExecutions() {
        return totalExecutions.get();
    }

    public long getTotalErrors() {
        return totalErrors.get();
    }

    public double getAverageDurationMs() {
        long count = totalExecutions.get();
        if (count == 0) {
            return 0;
        }
        return (totalDurationNanos.get() / 1_000_000.0) / count;
    }

    public String getLastExecutionAt() {
        return lastExecutionAt;
    }

    /**
     * Records the outcome of a script step execution.
     */
    public void recordExecution(long durationNanos, boolean error) {
        totalExecutions.incrementAndGet();
        totalDurationNanos.addAndGet(durationNanos);
        if (error) {
            totalErrors.incrementAndGet();
        }
        lastExecutionAt = java.time.Instant.now().toString();
    }
}
