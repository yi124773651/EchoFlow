/**
 * Application layer — use-case orchestration.
 *
 * <p>This layer coordinates domain logic, manages transaction boundaries
 * ({@code @Transactional}), and publishes domain events.  It depends only
 * on the domain module and Spring transaction / context support.</p>
 */
package com.echoflow.application;
