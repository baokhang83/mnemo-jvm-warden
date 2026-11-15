// Interactive shrink/grow handshake. Progressive enhancement: the page reads fine without it.
(function () {
  var root = document.getElementById('seq');
  if (!root) return;
  var DATA = {
    shrink: [
      'The controller resolves the schedule (lead time before the off-peak edge) and writes the target request/limit as annotations on the pod. The agent polls its own pod every 5 s.',
      'The agent lowers <code>SoftMaxHeapSize</code> (ZGC/Shenandoah) and asks an optional cache hook to flush only evictable entries. The cgroup is untouched.',
      'It forces a deep GC and uncommit so freed pages go back to the OS (G1: periodic GC and uncommit; no live soft-max).',
      'The JVM’s resident memory falls; the agent reads the real number from the cgroup (<code>memory.current</code>) and JVM telemetry, not from the GC’s say-so.',
      '<strong>The gate.</strong> Only if RSS is below the new limit (with a safety margin) does the shrink continue. Otherwise it aborts, restores the soft max and leaves the cgroup alone.',
      'Only now does the agent PATCH <code>pods/resize</code>, lowering request <em>and</em> limit. The request is what frees schedulable room for the Cluster Autoscaler.',
      'The kubelet applies it and the agent confirms. The cgroup was lowered only after RSS was confirmed.'
    ],
    grow: [
      'Traffic is predicted to return (lead time before the peak edge). The controller writes the peak target to the pod.',
      'Grow is the mirror image, not the same order: the agent raises the cgroup (request and limit) <strong>first</strong>.',
      'The kubelet grants the headroom and the agent confirms it.',
      'Only then does the agent raise <code>SoftMaxHeapSize</code>, so the JVM never reaches for memory that does not exist yet.',
      'Optionally it pre-warms the cache ahead of the predicted load, because a flushed cache is itself a cold start.'
    ],
    wrong: [
      'The wrong order: the pod’s memory limit is lowered while the JVM still holds more committed heap than the new limit.',
      'The kernel kills the process as soon as it touches memory that no longer fits: <code>OOMKilled</code>, exit code 137. The kubelet tries to hold a limit decrease back while usage is above it, but Kubernetes documents that check as best-effort with no guarantee. A JVM’s committed heap counts as used even when mostly free.'
    ]
  };
  var scenarios = root.querySelectorAll('.scenario');
  var tabs = root.querySelectorAll('.tabs button');
  var detail = root.querySelector('.detail');
  var count = root.querySelector('.count');
  var cur = 'shrink', idx = 0;

  function steps() { return root.querySelectorAll('.scenario[data-name="' + cur + '"] .step'); }
  function render() {
    var st = steps(), n = st.length;
    st.forEach(function (s, i) {
      s.classList.toggle('active', i === idx);
      s.classList.toggle('dim', i !== idx);
    });
    detail.innerHTML = '<p>' + DATA[cur][idx] + '</p>';
    count.textContent = 'Step ' + (idx + 1) + ' of ' + n;
  }
  function setMode(m) {
    cur = m; idx = 0;
    tabs.forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.mode === m)); });
    scenarios.forEach(function (s) { s.hidden = s.dataset.name !== m; });
    steps().forEach(function (s, i) { s.addEventListener('click', function () { idx = i; render(); }); });
    render();
  }
  tabs.forEach(function (b) { b.addEventListener('click', function () { setMode(b.dataset.mode); }); });
  root.querySelector('.prev').addEventListener('click', function () { idx = Math.max(0, idx - 1); render(); });
  root.querySelector('.next').addEventListener('click', function () { idx = Math.min(steps().length - 1, idx + 1); render(); });
  setMode('shrink');
})();
