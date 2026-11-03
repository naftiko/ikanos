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

/**
 * API of the durable execution engine embedded in Ikanos.
 *
 * <p>An {@link io.ikanos.durable.Interpreter} walks an execution tree and returns
 * {@link io.ikanos.durable.Command commands}; a {@link io.ikanos.durable.Runner} carries them out,
 * inline for the {@code none} mode or through a journal for the durable modes. Actions are run by
 * an {@link io.ikanos.durable.ActionExecutor} and addressed by a stable
 * {@link io.ikanos.durable.NodePath}.</p>
 *
 * <p>This package depends on the JDK only and carries no Ikanos concept: action descriptors,
 * inputs and results are opaque objects. The build enforces both rules.</p>
 */
package io.ikanos.durable;
