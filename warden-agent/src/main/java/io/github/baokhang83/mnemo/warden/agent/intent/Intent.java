package io.github.baokhang83.mnemo.warden.agent.intent;

/** The controller's resolved target for this pod's target container (W-306). */
public record Intent(long requestBytes, long limitBytes) {}
