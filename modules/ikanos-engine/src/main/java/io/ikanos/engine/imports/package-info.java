/**
 * Factorized import resolution — one generic resolver for all four sections
 * ({@code consumes}, {@code exposes}, {@code aggregates}, {@code binds}).
 *
 * <p>Replaces the section-specific {@code ConsumesImportResolver} with a shared
 * {@link io.ikanos.engine.imports.ImportResolver} driven by per-section
 * {@link io.ikanos.engine.imports.ImportStrategy} implementations.</p>
 *
 * @see io.ikanos.engine.imports.ImportResolver
 * @see io.ikanos.engine.imports.ImportStrategy
 */
package io.ikanos.engine.imports;
