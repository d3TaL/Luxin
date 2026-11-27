package io.github.d3tal.luxin.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class TrackingLogFilterTest {
    private val pid = 1234
    private fun native(message: String) = "  815.123456  1234  1240 I LuxinTrackerJNI: $message"
    private fun capture(message: String) = "  815.123456  1234  1241 D LuxinCapture: $message"

    @Test fun preservesMotionAggregatesAndOpenCvVersionWithoutPrivateFields() {
        val step = "BigMapMotion stage=step tsNs=815123456789 gen=2 ok=1 reason=static_pixels corners=250 tracked=240 inliers=230 " +
            "scaleStep=1.0003 txStep=0.02 tyStep=-0.01 lkMs=12 ransacMs=1 totalMs=18 convertMs=3 " +
            "pathSinceAbs=12 zoomSinceAbs=0.1 absAgeS=2 bridge=0 chainEpoch=7 hidden=0"
        val clean = TrackingLogFilter.sanitize(native(step), pid)!!
        assertTrue(clean.endsWith(step))
        for (stage in listOf("abs_request", "abs_result", "abs_apply", "abs_conflict", "lost"))
            assertTrue(TrackingLogFilter.sanitize(native("BigMapMotion stage=$stage outcome=suspect jumpPx=150 conflicts=1"), pid)!!.contains("stage=$stage"))
        val control = "BigMapMotion stage=control roi=0 outcome=unverifiable screenFixed=0 coverage=1 " +
            "warpedResidual=6.7 fixedResidual=18.5 warpedFrac=0.05 fixedFrac=0.26"
        assertTrue(TrackingLogFilter.sanitize(native(control), pid)!!.endsWith(control))
        assertTrue(TrackingLogFilter.sanitize(native("BigMapMotion stage=init opencv=4.10.0"), pid)!!.contains("opencv=4.10.0"))
        val unsafe = TrackingLogFilter.sanitize(native("BigMapMotion stage=init opencv=/data/private uid=123 x=42 matrix=[1,0]"), pid)!!
        assertFalse(unsafe.contains("opencv=") || unsafe.contains("uid=") || unsafe.contains("x=") || unsafe.contains("matrix="))
    }

    @Test
    fun preservesMotionFailureTimingAndPublicationFields() {
        val text = native("BigMap stage=return ts=815.123456789 frame=2340x1080 phase=moving mode=found " +
            "absolute=0 follow=tile_change sparseOk=1 movementPx=4.6 stable=0 measuredMotion=1 tolerated=1 " +
            "followMs=100.1 settle=not_attempted refresh=none")
        val filtered = TrackingLogFilter.sanitize(text, pid)!!
        assertTrue(filtered.startsWith("815.123456 1234 1240 I LuxinTrackerJNI: BigMap "))
        for (field in listOf("ts=815.123456789", "follow=tile_change", "tolerated=1", "measuredMotion=1",
                "frame=2340x1080", "followMs=100.1")) assertTrue(filtered.contains(field))
        val published = TrackingLogFilter.sanitize(capture("BigMap stage=publish epoch=7 tsNs=815123456789 " +
            "gen=2 rev=3 obsolete=false blocked=false phase=moving geometry=true"), pid)!!
        assertTrue(published.contains("geometry=true") && published.contains("tsNs=815123456789"))
    }

    @Test
    fun preservesFollowControlsNegativeSentinelsAndNonfiniteDiagnostics() {
        val follow = TrackingLogFilter.sanitize(native("BigMapFollow stage=return outcome=tile_change work=1560x720 " +
            "kp=1200/1199 desc=1200/1199 matches=600 inliers=500 ratio=0.833 grid=8 errorPx=0.4 " +
            "scale=1.000 angleDeg=-0.002 hullFrac=0.3 overlap=100000 overlapFrac=0.2 denseFrac=0.01 " +
            "worstVisitedTile=0,180,90,90 tileChanged=50 tileValid=400 tileFrac=0.125"), pid)!!
        assertTrue(follow.contains("kp=1200/1199"))
        assertTrue(follow.contains("worstVisitedTile=0,180,90,90"))
        val control = TrackingLogFilter.sanitize(native("BigMapControl stage=return ts=815.1 roi=1 " +
            "rect=0,0,0,0 mean=-1 changed=-1 pixels=0 frac=-1"), pid)!!
        assertTrue(control.contains("mean=-1") && control.contains("frac=-1"))
        assertTrue(TrackingLogFilter.sanitize(native("BigMap movementPx=nan followMs=inf errorPx=NaN"), pid)!!
            .contains("movementPx=nan"))
    }

    @Test
    fun preservesPixelOutcomeOfToleratedFollowButOnlyKnownValues() {
        val follow = TrackingLogFilter.sanitize(native("BigMapFollow stage=return outcome=controls_change " +
            "denseFrac=0.03 pixelOutcome=tile_change"), pid)!!
        assertTrue(follow.contains("outcome=controls_change") && follow.contains("pixelOutcome=tile_change"))
        assertTrue(TrackingLogFilter.sanitize(native("BigMapFollow stage=return outcome=accepted pixelOutcome=none"), pid)!!
            .contains("pixelOutcome=none"))
        val unknown = TrackingLogFilter.sanitize(native("BigMapFollow stage=return outcome=accepted " +
            "pixelOutcome=/data/private"), pid)!!
        assertFalse(unknown.contains("pixelOutcome"))
    }

    @Test
    fun dropsPathsUidCoordinatesRawJsonAndArbitraryMessages() {
        val filtered = TrackingLogFilter.sanitize(native("BigMap stage=return ts=12.5 " +
            "path=/storage/emulated/0/private.png uid=123456789 x=-1000 y=2000 " +
            "space=my-secret-space matrix=[1,0,0,0,1,0] token=secret phase=private_name " +
            "reason=/data/private mode=found"), pid)!!
        assertEquals("815.123456 1234 1240 I LuxinTrackerJNI: BigMap stage=return ts=12.5 mode=found", filtered)
        assertNull(TrackingLogFilter.sanitize(capture("Live 2340x1080 pos=found x=-1000 y=2000"), pid))
        assertNull(TrackingLogFilter.sanitize(capture("Saved screenshot to /storage/private.png"), pid))
        assertNull(TrackingLogFilter.sanitize(native("{\"mode\":\"found\",\"matrix\":[1,0,0]}"), pid))
        assertNull(TrackingLogFilter.sanitize("java.io.IOException: private error", pid))
    }

    @Test
    fun neverExportsAnotherProcessOrTag() {
        assertNull(TrackingLogFilter.sanitize(native("BigMap stage=return").replace("1234", "9999"), pid))
        assertNull(TrackingLogFilter.sanitize(native("BigMap stage=return").replace("LuxinTrackerJNI", "OtherApp"), pid))
        assertNull(TrackingLogFilter.sanitize("11-21 12:00:00.000 1234 1240 I LuxinTrackerJNI: BigMap stage=return", pid))
        assertNull(TrackingLogFilter.sanitize(native("BigMap phase=tracking\u0000secret"), pid))
    }

    @Test
    fun layerDiagnosticsKeepBooleanButNotHeuristicNamesOrReasons() {
        val filtered = TrackingLogFilter.sanitize(capture("BigMapV2 layer=LayerDecision(layerId=layer_700, " +
            "isSurface=false, mode=layer, reason=private account name, confirmed=false) " +
            "mode=big_map phase=tracking version=2"), pid)!!
        assertTrue(filtered.contains("layerConfirmed=false"))
        assertTrue(filtered.contains("mode=big_map") && filtered.contains("phase=tracking"))
        assertFalse(filtered.contains("layer_700") || filtered.contains("private") || filtered.contains("account"))
    }

    @Test
    fun boundedStreamingSkipsOversizedLineAndKeepsNextCompleteRecord() {
        val output = StringWriter()
        val summary = TrackingLogFilter.copy(StringReader("x".repeat(TrackingLogFilter.MAX_LINE_CHARS + 20) +
            "\n" + native("BigMap phase=moving tolerated=1") + "\n"), output, pid)
        assertEquals(1, summary.records)
        assertFalse(summary.limited)
        assertTrue(output.toString().contains("tolerated=1"))
        assertTrue(output.toString().length < 200)
    }

    @Test
    fun limitsRejectedInputAndNeverWritesHalfARecord() {
        val output = StringWriter()
        val summary = TrackingLogFilter.copy(StringReader(native("BigMap phase=tracking")), output, pid,
            maxInputChars = 20)
        assertTrue(summary.limited)
        assertEquals(0, summary.records)
        assertEquals("", output.toString())
    }

    @Test
    fun capsRecordsWithoutDuplicatingOrContinuingPastLimit() {
        val output = StringWriter()
        val summary = TrackingLogFilter.copy(StringReader((native("BigMap phase=tracking") + "\n").repeat(3)),
            output, pid, maxRecords = 2)
        assertTrue(summary.limited)
        assertEquals(2, summary.records)
        assertEquals(2, output.toString().lineSequence().count { it.isNotEmpty() })
    }

    @Test
    fun supportsFinalLineWithoutNewlineAndEmptyBuffer() {
        val output = StringWriter()
        assertEquals(1, TrackingLogFilter.copy(StringReader(native("BigMap lifecycle=stop")), output, pid).records)
        assertEquals(TrackingLogSummary(0, false), TrackingLogFilter.copy(StringReader(""), StringWriter(), pid))
    }
}
