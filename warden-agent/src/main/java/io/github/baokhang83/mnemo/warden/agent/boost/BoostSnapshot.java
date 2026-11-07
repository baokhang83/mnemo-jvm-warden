package io.github.baokhang83.mnemo.warden.agent.boost;

/**
 * One GET/CREATE/PATCH observation of a {@code WardenBoost}, as the local API needs it &mdash;
 * not the full CRD shape, the same "read only the leaves this caller needs" posture {@code
 * PodState} already takes for pods.
 *
 * @param name the {@code WardenBoost}'s {@code metadata.name} &mdash; the stable request ID
 * @param resourceVersion fences a later {@code completionRequested} PATCH against races
 * @param phase the controller-owned lifecycle phase, as a raw string (e.g. {@code "Granted"});
 *     never parsed into an enum here &mdash; {@code warden-agent} deliberately has no dependency
 *     on {@code warden-crd-model} (zero-extra-runtime-dependency posture)
 * @param message the most recent status condition's message, if any, for the caller to surface
 */
public record BoostSnapshot(String name, String resourceVersion, String phase, String message) {}
