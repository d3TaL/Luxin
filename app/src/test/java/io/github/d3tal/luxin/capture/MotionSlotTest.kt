package io.github.d3tal.luxin.capture

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import io.github.d3tal.luxin.tracker.*

class MotionSlotTest {
    @Test fun replacementWaitsForRetiringNativeOwner() = runBlocking {
        val owner = TrackingOwner(); val release = CompletableDeferred<Unit>(); val events = mutableListOf<String>()
        val old = launch(start=CoroutineStart.UNDISPATCHED) { owner.run {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await(); events.add("closed") } }
        } }
        old.cancel()
        val next = launch(start=CoroutineStart.UNDISPATCHED) { owner.run { events.add("new") } }
        assertTrue(events.isEmpty()); release.complete(Unit); next.join(); old.join()
        assertEquals(listOf("closed","new"),events)
    }
    @Test fun queuedFrameRoutedToMinimapCannotPublishAfterBriefPauseResume() {
        val frame = BigMapFrame(4,2,100,780,360)
        val state = LiveTrackingState(enabled=true,screenMode="minimap",status="tracking")
        assertEquals(Publication.OBSOLETE,publishTrackingResult(state,1,1,frame,5,2,200,null) {
            assertNull(it.position); assertEquals("paused",it.status)
        })
    }
    @Test fun replacingAndClosingNeverReleasesFrameInUse() {
        val closed = mutableListOf<Int>()
        val slot = MotionSlot<Closeable>()
        fun offer(i: Int) = slot.offer(i.toLong(), Closeable { closed.add(i) })
        offer(1); val inNative = slot.take()!!
        offer(3); offer(2); offer(4); slot.close(); offer(5)
        assertNull(slot.take())
        assertEquals(listOf(2,3,4,5),closed)
        inNative.close()
        assertEquals(1,closed.last())
    }
    @Test fun onlyConfirmedMapCanReuseRecentObservation() {
        assertTrue(needsMotionGate(false,10,11)); assertTrue(needsMotionGate(true,-1,11))
        assertFalse(needsMotionGate(true,10,300_000_009)); assertTrue(needsMotionGate(true,10,300_000_010))
        assertTrue(needsMotionGate(true,10,9))
    }
}
