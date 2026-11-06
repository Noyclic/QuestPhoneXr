"""Frame pacing helpers for the EndVR host."""


def advance_frame_deadline(previous_deadline: float, period: float, now: float) -> float:
    """Advance one frame period and skip elapsed slots after an overrun.

    This prevents slow frame generation or socket writes from causing a busy
    loop that tries to render a backlog of stale frames.
    """
    if period <= 0:
        raise ValueError("frame period must be positive")

    next_deadline = previous_deadline + period
    if next_deadline < now:
        missed_slots = int((now - next_deadline) // period) + 1
        next_deadline += missed_slots * period
    return next_deadline
