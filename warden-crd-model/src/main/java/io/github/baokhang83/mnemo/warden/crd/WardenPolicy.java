package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The {@code WardenPolicy} custom resource: a declarative schedule of {@link ResourceProfile}
 * transitions for one target workload (constitution: M3's whole reason for existing). {@code
 * Namespaced}, not cluster-scoped &mdash; a policy governs one workload in one namespace, the
 * same scope {@link TargetRef} names.
 *
 * <p>No {@code @Kind} annotation: the generated Kind already matches this class's own name
 * ({@code WardenPolicy}), so declaring it again would be redundant (§1).
 */
@Group("warden.mnemo.io")
@Version("v1alpha1")
public class WardenPolicy extends CustomResource<WardenPolicySpec, WardenPolicyStatus> implements Namespaced {}
