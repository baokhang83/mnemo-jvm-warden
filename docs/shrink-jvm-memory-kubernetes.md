# How to shrink JVM memory in Kubernetes without a restart

**Short answer:** make the JVM give memory back first, verify that the process's resident
memory (RSS) actually dropped, and only then lower the pod's memory request and limit with
Kubernetes in-place pod resize (GA in Kubernetes 1.35). Lowering the pod limit first is what
gets Java pods OOMKilled. [mnemo-jvm-warden](../README.md) automates this order on a schedule,
and runs the reverse order (pod first, then JVM) when memory grows back.

This page answers the questions people search for:

- [How do I shrink a JVM pod's memory without restarting it?](#how-do-i-shrink-a-jvm-pods-memory-without-restarting-it)
- [Why was my JVM OOMKilled after an in-place resize on Kubernetes 1.35?](#why-was-my-jvm-oomkilled-after-an-in-place-resize-on-kubernetes-135)
- [Why not just use `RestartContainer`?](#why-not-just-use-restartcontainer)
- [How small can the memory limit go?](#how-small-can-the-memory-limit-go)
- [Why isn't VPA enough for a JVM?](#why-isnt-vpa-enough-for-a-jvm)
- [Try it](#try-it)

## How do I shrink a JVM pod's memory without restarting it?

Two things have to be true at once, and Kubernetes only controls one of them:

1. **The container's memory request and limit change in place.** Since Kubernetes 1.35 the
   `pods/resize` subresource is GA, and a container with `resizePolicy` set to
   `restartPolicy: NotRequired` for memory is resized without being recreated.
2. **The JVM has already released the memory.** The JVM does not return committed heap to the
   operating system on its own schedule. Kubernetes cannot make it do so.

So the order matters:

| Step | Shrink | Grow (reverse) |
|---|---|---|
| 1 | Lower the JVM's soft heap ceiling and force a GC | Raise the pod's memory request and limit |
| 2 | Wait for the pages to leave the process; verify RSS fell below the new limit | Wait for the cgroup to show the new headroom |
| 3 | Only then shrink the pod's request and limit in place | Raise the JVM's soft heap ceiling |

Warden's agent runs this sequence inside each pod, so the controller only has to say *when*.
See [Architecture](architecture.md) for the full handshake and [Warden Boost](warden-boost.md)
for letting an application ask for memory for a bounded job.

## Why was my JVM OOMKilled after an in-place resize on Kubernetes 1.35?

There are two different causes. Check which one you have:

```bash
kubectl describe pod <pod> | grep -A5 'Last State'
# Reason: OOMKilled, Exit Code: 137
```

**Cause 1: the limit dropped before the JVM released memory.** The container limit was
lowered while the JVM still held more committed heap than the new limit. The kernel kills the
process as soon as it touches memory that no longer fits. Fix: release first, verify RSS, then
resize. This is the order described above.

Kubernetes does not protect you from this. When a memory limit is decreased, the kubelet
tries to avoid an OOM kill by holding back the resize while the container's current memory
usage is above the new limit. The Kubernetes documentation describes that check as
best-effort and gives no guarantee: usage can still spike right after the check. See the
[Kubernetes 1.35 announcement](https://kubernetes.io/blog/2025/12/19/kubernetes-v1-35-in-place-pod-resize-ga)
and the [resize documentation](https://kubernetes.io/docs/tasks/configure-pod-container/resize-container-resources/).
With a JVM, committed heap counts as memory in use even when most of it is free, so the check
will usually keep the resize pending until the JVM has released memory. That release is the step
Warden performs first.

**Cause 2: the new limit is below what `-Xmx` allows.** `-Xmx` is fixed when the JVM starts;
nothing can change it on a running process. The limit covers the whole resident set, not only
the heap:

```
RSS ≈ live heap + metaspace + code cache + thread stacks + direct/off-heap buffers
```

If `-Xmx` plus the non-heap part is larger than the smallest limit you ever apply, the pod
survives the shrink and is killed later, when the working set grows again. Fix: size the
smallest limit as shown in the next section.

## Why not just use `RestartContainer`?

Kubernetes lets you set `resizePolicy` per resource. For memory, setting the policy to
`RestartContainer` restarts the container to apply the new value, and the
[resize documentation](https://kubernetes.io/docs/tasks/configure-pod-container/resize-container-resources/)
says this is often necessary for memory because many runtimes cannot adjust dynamically.
For a JVM that is a safe choice: the new process starts with a heap sized for the new limit.

The cost is that the JVM restarts. Compiled code, loaded classes, connection pools and
in-process caches are gone and have to warm up again, and a service with few replicas loses
capacity while it does. If that is acceptable, `RestartContainer` is simpler and needs no extra
tooling. Warden is for the cases where it is not: slow-starting applications, minimum-replica
setups, and stateful or cache-heavy workloads. There, the pod and the JVM stay up, and the JVM
is asked to release memory before the limit changes.

## How small can the memory limit go?

```
smallest profile limit
    ≥ -Xmx
    + non-heap overhead (commonly 100–150 MiB for a Spring Boot service)
    + working-set headroom
```

Example from the [walkthrough](https://baokhang.hashnode.dev/shrink-jvm-memory-kubernetes-in-place-resize):
`-Xmx200m` plus about 130 MiB non-heap is about 330 MiB expected RSS, so a 400 MiB
off-peak limit leaves about 70 MiB of headroom.

Warden does not save memory by squeezing an application into a smaller container than it
needs. It saves memory when the application's actual working set shrinks during quiet hours,
and the container shrinks to match. Lower the **request**, not only the limit: the request is
what the scheduler and the cluster autoscaler see.

## Why isn't VPA enough for a JVM?

The Vertical Pod Autoscaler can resize pods in place. What it cannot do is make the JVM
release memory: it observes RSS and recommends a size, but it has no way to lower the
JVM's heap, run a GC, or check that memory left the process before resizing. Warden adds that
JVM-side step and the schedule-aware timing. A side-by-side table is in the README under
[Warden vs. VPA](../README.md#warden-vs-vpa).

## Try it

Install the controller once per cluster, then add a `WardenPolicy` and the sidecar to your
Deployment:

```bash
helm repo add warden https://baokhang83.github.io/mnemo-jvm-warden/
helm repo update
helm install warden warden/warden
```

- Full walkthrough with a three-replica Spring Boot demo on `kind`:
  [Your JVMs Are Burning Money Overnight](https://baokhang.hashnode.dev/shrink-jvm-memory-kubernetes-in-place-resize)
- Configuration reference: [configuration.md](configuration.md)
- Which garbage collectors are supported: ZGC and Shenandoah expose a settable soft heap
  ceiling; G1 relies on forcing a GC and waiting for committed memory to drop, which is safe
  but slower. The Serial collector is not supported.
- Requires Kubernetes 1.35+ (or the `InPlacePodVerticalScaling` feature gate on older
  versions) and Java 21 for the optional [lease client](warden-boost.md#java-lease-client-b9).
