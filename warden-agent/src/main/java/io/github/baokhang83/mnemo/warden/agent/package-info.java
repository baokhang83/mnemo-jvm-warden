/**
 * Sidecar agent: the safety-critical executor. It controls the target JVM's heap (release,
 * verify RSS, uncommit) and performs the in-place Pod resize.
 *
 * <p>It acts on a simple instruction (a target profile handed to it by the controller), not on
 * the {@code WardenPolicy} itself &mdash; hence no dependency on {@code warden-crd-model}. That
 * keeps the sidecar lean. The {@code HeapController} port and resize logic arrive in M1/M2.
 */
package io.github.baokhang83.mnemo.warden.agent;
