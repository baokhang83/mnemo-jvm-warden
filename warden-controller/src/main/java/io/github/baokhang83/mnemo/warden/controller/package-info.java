/**
 * Cluster-scoped controller: watches {@code WardenPolicy} resources and reconciles them
 * against a traffic schedule, emitting shrink/grow intent to the agents.
 *
 * <p>This is the only module that depends on {@code warden-crd-model} &mdash; it owns the CRD
 * complexity so the agent doesn't have to. The reconciler itself arrives in W-302.
 */
package io.github.baokhang83.mnemo.warden.controller;
