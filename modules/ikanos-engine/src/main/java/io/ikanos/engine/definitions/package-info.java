/**
 * Load-time expansion of shared definitions (#910).
 *
 * <p>An MCP adapter or an aggregate may declare a {@code definitions} block of named input
 * parameter sets and schemas, reused by its own tools or flows through
 * {@code inputParameterSets: [names]} and {@code schema: name}.
 * {@link io.ikanos.engine.definitions.DefinitionsExpander} replaces every use with the inline
 * equivalent after imports and before {@code ref} resolution, so the rest of the engine, and
 * the MCP clients, never see a reference.</p>
 *
 * @see io.ikanos.engine.definitions.DefinitionsExpander
 */
package io.ikanos.engine.definitions;
