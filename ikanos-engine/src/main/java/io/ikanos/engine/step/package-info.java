/**
 * Embedding API for registering Java step handlers that execute in-process during orchestration.
 *
 * <p>This package provides the programmatic interface for using the Naftiko Framework as a library.
 * Embedders register {@link io.ikanos.engine.step.StepHandler} implementations by step name via
 * {@link io.ikanos.engine.step.StepHandlerRegistry}; when the engine encounters a registered step
 * during execution, it delegates to the handler instead of the normal call/script/lookup path.
 */
package io.ikanos.engine.step;
