/**
 * Cluster-scoped controller: watches {@code WardenPolicy} resources and reconciles them
 * against a traffic schedule, emitting shrink/grow intent to the agents.
 *
 * <p>This is the only module that depends on {@code warden-crd-model} &mdash; it owns the CRD
 * complexity so the agent doesn't have to. {@link
 * io.github.baokhang83.mnemo.warden.controller.WardenPolicyReconciler} (W-302) is the skeleton;
 * real schedule/lead-time/blackout/guardrail evaluation and agent handoff land in later M3/M4
 * tickets.
 */
package io.github.baokhang83.mnemo.warden.controller;
