"""Explicit video clock: preserve legacy FPS timing, support real PTS for VFR recordings."""
import math

import cv2


class VideoClock:
    def __init__(self, fps, mode="fps"):
        if mode not in ("fps", "pts") or not math.isfinite(fps) or fps <= 0:
            raise ValueError("invalid video clock")
        self.fps, self.mode, self.previous = fps, mode, None

    def at(self, cap, index):
        t = index / self.fps if self.mode == "fps" else cap.get(cv2.CAP_PROP_POS_MSEC) / 1000
        if not math.isfinite(t) or t < 0:
            raise ValueError("invalid frame timestamp")
        if self.previous is not None and t + 1e-6 < self.previous:
            raise ValueError("non-monotonic frame timestamps; no silent clock fallback")
        self.previous = t
        return t


def next_sample_time(t, start, rate):
    """Next sampling-grid tick AFTER this frame, skipping missed ticks rather than bursting."""
    if not all(math.isfinite(v) for v in (t, start, rate)) or rate <= 0 or t < start - 1e-6:
        raise ValueError("invalid sampling grid")
    return start + (math.floor(max(0.0, t - start) * rate + 1e-6) + 1) / rate
