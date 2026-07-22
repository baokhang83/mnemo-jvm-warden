# Design: W-604 — Docs: configuration reference + runbook

started: 2026-07-22

This is a docs-only ticket (no new code): `docs/configuration.md` documenting every
`WardenPolicy` field plus an operator runbook. The two diagrams below are the shape the
document itself walks through, not new classes being introduced.

## Class diagram — the schema the reference documents

```mermaid
classDiagram
  class WardenPolicySpec {
    +TargetRef targetRef
    +String timezone «required»
    +Map~String,ResourceProfile~ profiles
    +List~ScheduleWindow~ schedule
    +LeadTime leadTime
    +List~BlackoutWindow~ blackout
    +Guardrail guardrail
  }
  class TargetRef {
    +String apiVersion
    +String kind
    +String name
  }
  class ResourceProfile {
    +String request
    +String limit
  }
  class ScheduleWindow {
    +String cron
    +String profile
  }
  class LeadTime {
    +String shrink
    +String warm
  }
  class BlackoutWindow {
    +String start
    +String end
  }
  class Guardrail {
    +String metric
    +String shrinkBelow
    +String emergencyGrowAbove
  }
  class WardenPolicyStatus {
    +String currentProfile
    +Double currentMetricValue
  }
  WardenPolicySpec --> TargetRef
  WardenPolicySpec --> ResourceProfile : profiles (keyed)
  WardenPolicySpec --> ScheduleWindow : schedule (0..*)
  WardenPolicySpec --> LeadTime
  WardenPolicySpec --> BlackoutWindow : blackout (0..*)
  WardenPolicySpec --> Guardrail
```

## Sequence: the runbook's mental model (precedence + read-only gate)

The runbook section exists to answer "why isn't Warden doing what I expect" — this is the
one flow an operator needs in their head to answer that themselves.

```mermaid
sequenceDiagram
  participant R as WardenPolicyReconciler
  participant P as PrecedenceEngine
  participant A as warden-agent

  R->>R: evaluate metric (guardrail.metric)
  R->>R: blackout? schedule candidate? shrink veto? emergency grow?
  R->>P: resolve(blackedOut, emergencyGrow, scheduleCandidate, shrinkVetoed)
  P-->>R: resolved profile (or none)
  R->>A: PATCH pod intent annotation (resolved profile)
  A->>A: resolve target's HeapController (cached per attach)
  alt collector unsupported (Serial/Parallel/Epsilon)
    A-->>A: read-only — warden_gc_supported{collector=...} 0
  else supported (ZGC/Shenandoah/G1)
    A->>A: shrink: softmax -> flush caches -> deep GC+uncommit -> verify RSS -> resize
    A->>A: grow: resize -> raise softmax -> pre-warm caches
  end
```
