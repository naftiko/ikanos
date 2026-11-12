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
package io.ikanos.engine.exposes;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CacheScopesTest {

    @ParameterizedTest
    @ValueSource(strings = {"public", "private"})
    void resolveShouldKeepADeclaredScopeEvenWhenTheResultDependsOnTheCaller(String declared) {
        CacheScopes.Decision decision = CacheScopes.resolve(declared, "caller-dependent");

        assertThat(decision.scope()).isEqualTo(declared);
        assertThat(decision.reason()).isEqualTo("declared " + declared);
    }

    @Test
    void resolveShouldMakeAutoPrivateWhenTheResultDependsOnTheCaller() {
        CacheScopes.Decision decision = CacheScopes.resolve("auto", "the adapter authenticates");

        assertThat(decision.scope()).isEqualTo("private");
        assertThat(decision.reason()).contains("the adapter authenticates");
    }

    @Test
    void resolveShouldMakeAutoPublicWhenTheResultDoesNotDependOnTheCaller() {
        CacheScopes.Decision decision = CacheScopes.resolve("auto", null);

        assertThat(decision.scope()).isEqualTo("public");
        assertThat(decision.reason()).contains("does not depend on the caller");
    }
}
