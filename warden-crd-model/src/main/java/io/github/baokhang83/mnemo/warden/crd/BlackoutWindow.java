package io.github.baokhang83.mnemo.warden.crd;

/**
 * A hard "do not touch" window &mdash; W-305: beats both {@link ScheduleWindow schedule} and
 * (later) metric signals. ISO-8601 date/time strings, parsed and range-validated by the ticket
 * that actually evaluates blackouts, not here (§1).
 */
public class BlackoutWindow {

  private String start;
  private String end;

  public String getStart() {
    return start;
  }

  public void setStart(String start) {
    this.start = start;
  }

  public String getEnd() {
    return end;
  }

  public void setEnd(String end) {
    this.end = end;
  }
}
