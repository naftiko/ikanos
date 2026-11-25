/**
 * Load-time expansion of shared definitions (#910).
 *
 * <p>An MCP adapter, a REST adapter or an aggregate may declare a {@code definitions} block of
 * named input parameter sets, schemas and mapping sets, reused by its own units through
 * {@code inputParameterSets: [names]}, {@code schema: name} and, in a mapped output,
 * {@code mappingSets: [names]}.
 * {@link io.ikanos.engine.definitions.DefinitionsExpander} replaces every use with the inline
 * equivalent after imports and before {@code ref} resolution, so the rest of the engine, and
 * the MCP clients, never see a reference.</p>
 *
 * @see io.ikanos.engine.definitions.DefinitionsExpander
 */
package io.ikanos.engine.definitions;
