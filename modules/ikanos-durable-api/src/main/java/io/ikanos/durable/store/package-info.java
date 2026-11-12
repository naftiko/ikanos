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
 * Storage interfaces of the durable execution engine: journal, execution index, work queue,
 * signal inbox, blob store and payload codec. Store modules implement them; the {@code none} mode
 * uses none of them.
 *
 * <p><strong>Provisional.</strong> These signatures are a first cut for the E1 increment. The
 * engine's design doc and its store conformance suite will settle them before a store module
 * ships.</p>
 */
package io.ikanos.durable.store;
