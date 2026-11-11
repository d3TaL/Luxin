# Third-party notices

## cvAutoTrack — global/local tracking retry policy

- Project: https://github.com/GengGode/cvAutoTrack
- Version: 7.9.5, commit `23062c1049e357ffb3bf1515c9afcb66a45855b0`.
- Source: `cvAutoTrack/src/match/surf/SurfMatch.cpp`, `SurfMatch::match`.
- Adaptation: `spike/feature_tracker.py` (`FeatureTracker`) and `spike/split_tracker.py`
  (`SplitTracker`) use its global acquisition / local follow / retry-global policy.
  The Luxin adaptation uses SIFT instead of SURF, adds geometric
  and patch-support gates, rejects ambiguity, and never exposes the old position on failure.
  It is not the original cvAutoTrack tracker or its performance benchmark.
- License below was verified in this exact revision. No upstream binaries, map resources,
  descriptor caches or Windows capture code are included.

MIT License

Copyright (c) 2021 GengGode

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
