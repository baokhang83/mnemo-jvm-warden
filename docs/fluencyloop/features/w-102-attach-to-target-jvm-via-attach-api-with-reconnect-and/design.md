# Design: W-102 — Attach to target JVM

started: 2026-07-19

The agent needs a live control channel to the app JVM sharing its pod (`shareProcessNamespace:
true`, see `deploy/example-sidecar.yaml`). This slices into three parts: find the target PID,
attach to it and open an MBean connection, and supervise that connection for the target's whole
lifetime (including restarts), surfacing the result on `/readyz`.

## Class diagram

```mermaid
classDiagram
  class TargetLocator {
    +findTarget() Optional~VirtualMachineDescriptor~
  }
  class TargetAttacher {
    +attach(VirtualMachineDescriptor) AttachedJvm
  }
  class AttachedJvm {
    +long pid
    +MBeanServerConnection mbeanConnection()
    +boolean isAlive()
    +close()
  }
  class AttachSupervisor {
    -TargetLocator locator
    -TargetAttacher attacher
    -HealthState health
    +start()
    +stop()
    +currentTarget() Optional~AttachedJvm~
  }
  class HealthState {
    +ready() boolean
    +markReady()
    +markNotReady()
  }
  class GcDetector {
    +detect(List~GarbageCollectorMXBean~) GcCapabilities
  }
  class WardenAgent

  WardenAgent --> AttachSupervisor : starts on boot
  AttachSupervisor --> TargetLocator : uses
  AttachSupervisor --> TargetAttacher : uses
  AttachSupervisor --> AttachedJvm : holds current
  AttachSupervisor --> HealthState : updates
  AttachedJvm ..> GcDetector : MXBean proxies feed W-101's detector
```

## Sequence: attach on boot, reconnect after target restart

```mermaid
sequenceDiagram
  participant Agent as WardenAgent
  participant Sup as AttachSupervisor
  participant Loc as TargetLocator
  participant Att as TargetAttacher
  participant Target as Target JVM

  Agent->>Sup: start()
  loop until attached
    Sup->>Loc: findTarget()
    Loc-->>Sup: VirtualMachineDescriptor
    Sup->>Att: attach(descriptor)
    Att->>Target: VirtualMachine.attach(pid)
    Att->>Target: startLocalManagementAgent()
    Target-->>Att: JMX connector address
    Att-->>Sup: AttachedJvm
  end
  Sup->>Sup: health.markReady()

  loop liveness poll
    Sup->>Target: isAlive()?
  end

  Target--xTarget: process restarts
  Sup->>Sup: health.markNotReady()
  Sup->>Sup: close AttachedJvm
  Note over Sup,Loc: re-enter the attach loop above
```
