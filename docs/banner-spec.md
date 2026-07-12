# Banner / hero-flow spec

A precise spec for the marketing banner's flow diagram, so the next render is
technically accurate. The **one rule** that governs everything here:

> **Warden's action boundary ends at "lower the memory request."**
> It never changes pod count (HPA) or node count (Cluster Autoscaler).
> Node consolidation and cost savings are *emergent, conditional* outcomes
> Warden enables but does not perform.

Any layout that draws Warden marching all the way to "fewer nodes" as one
continuous pipeline is **inaccurate** and overclaims. The flow must show a hard
wall between what Warden does and what the cluster does.

---

## The two zones

The flow is two zones separated by a **hand-off boundary** (a dashed arrow / gap
/ divider — visually distinct from the solid arrows *inside* each zone).

### Zone A — WARDEN (solid arrows; this is the product)

Ordered, and the order is the whole safety story. Four beats:

| # | Panel title | Sub-label | Note |
|---|---|---|---|
| A1 | `JVM HEAP` | *start state* | This is a **state**, not an action — style it as the input, not a step. |
| A2 | `GC / UNCOMMIT` | Release memory | The real "release memory" step. |
| A3 | `VERIFY RSS` | ✓ safety gate | **Do not omit.** The differentiator: confirm RSS actually dropped *before* resizing. |
| A4 | `POD RESIZE` | Lower **request** (+ limit) | In place, no restart. Say **request**, never "limit." |

Solid arrows A1 → A2 → A3 → A4. The A3 → A4 arrow is the load-bearing one:
memory is verified out **before** the cgroup comes down (otherwise: OOMKill).

### Boundary

A **dashed** arrow `⇢` or a labelled divider: **"hand-off — Kubernetes takes
over."** Not a numbered step.

### Zone B — KUBERNETES (dimmed / outlined; explicitly *not* Warden)

Style this zone as clearly secondary (muted colour, dashed border, a
"requires Cluster Autoscaler" tag). Beats:

| # | Panel title | Sub-label | Note |
|---|---|---|---|
| B1 | `BIN-PACK` | scheduler reclaims capacity | Kubernetes scheduler, not Warden. |
| B2 | `FEWER NODES` | Cluster Autoscaler consolidates | **Conditional** — needs a CA. |
| B3 | `SAVE $` | infrastructure cost drops | The outcome / "so what." |

---

## What the current banner got wrong (change list)

1. **Off-by-one labels.** The bottom chips (`Release memory / Lower requests /
   Consolidate nodes / Save infrastructure`) don't register 1:1 with the top
   panels; read as pairs they're shifted a panel right. Fix: one label per
   panel, aligned, per the tables above.
2. **Missing the verify gate.** There is no `VERIFY RSS` beat — the safety
   differentiator is invisible. Add panel A3.
3. **`FEWER NODES` shown as Warden's output.** It's in Warden's colour and flow.
   Move it into the dimmed Zone B, behind the hand-off boundary, tagged
   "requires Cluster Autoscaler."
4. **`JVM HEAP` styled as a step.** It's the input state; style it distinctly
   from the action panels.

## Keep (these were right)

- **"Lower requests," not "lower limits."** This is correct and important — keep it.
- The left-to-right causal ordering is correct.
- The feature strip (In-place Pod Resize · JVM-aware · Schedule-driven · No cold
  starts) is accurate; leave it.
- Logo, tagline ("Keep pods warm. Give memory back."), colour language — all fine.

---

## Reference: the corrected flow in one line

```
[JVM HEAP] → [GC / UNCOMMIT] → [✓ VERIFY RSS] → [POD RESIZE: lower request]
   state       release mem       safety gate        ── Warden ends here ──
        ⇣ hand-off (needs Cluster Autoscaler) ⇣
[BIN-PACK] → [FEWER NODES] → [SAVE $]        ← Kubernetes, dimmed, conditional
```
