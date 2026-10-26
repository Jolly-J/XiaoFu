package dev.local.audio

import org.junit.Assert.*
import org.junit.Test

class PredictiveMotionTest {
    @Test fun endpointsRevealExactlyOnePage() {
        val start = backLayers(0.0)
        assertEquals(1f, start.foregroundAlpha, 0f)
        assertEquals(0f, start.backgroundAlpha, 0f)
        assertEquals(1f, start.maskAlpha, 0f)
        assertEquals(0f, start.foregroundXFraction, 0f)
        val end = backLayers(1.0)
        assertEquals(0f, end.foregroundAlpha, 0f)
        assertEquals(1f, end.backgroundAlpha, 0f)
        assertEquals(0f, end.maskAlpha, 0f)
        assertEquals(0f, end.backgroundXFraction, 0f)
    }
    @Test fun sourceStageBoundariesPreventEarlyBleed() {
        for (p in listOf(0.0, .1, .25, .36)) assertEquals(0f, backLayers(p).backgroundAlpha, 0f)
        for (p in listOf(.46, .5, .75, 1.0)) assertEquals(0f, backLayers(p).foregroundAlpha, 0f)
        val middle = backLayers(.46)
        val visibleIncoming = middle.backgroundAlpha * (1 - middle.maskAlpha)
        assertTrue("The intermediate frame must be nearly pure themed background", visibleIncoming < .004f)
        assertTrue(backLayers(.7).backgroundAlpha > backLayers(.46).backgroundAlpha)
    }
    @Test fun translationAndAlphaStayWithinReferenceBoundsInBothDirections() {
        val forward = (0..100).map { backLayers(it / 100.0) }
        assertEquals(forward.reversed(), (100 downTo 0).map { backLayers(it / 100.0) })
        forward.forEach {
            assertTrue(it.foregroundXFraction in 0f..0.130001f)
            assertTrue(it.backgroundXFraction in -0.080001f..0f)
            assertTrue(it.foregroundAlpha in 0f..1f)
            assertEquals(1f, it.backgroundAlpha + it.maskAlpha, .000001f)
        }
    }
    @Test fun draggingLagsTargetAndKeepsMomentumWhenReversing() {
        val spring = BackProgressSpring()
        val target = dampedBackTarget(.5)
        val first = spring.step(target, 1.0 / 60)
        assertTrue(first > 0 && first < target * .1)
        repeat(12) { spring.step(target, 1.0 / 60) }
        val before = spring.position
        spring.step(0.0, 1.0 / 60)
        assertTrue(spring.position > 0.0)
        assertTrue(kotlin.math.abs(spring.position - before) < .1)
        repeat(120) { spring.step(0.0, 1.0 / 60) }
        assertTrue(spring.position < .00001)
    }
    @Test fun longFramesAreCappedAndRawProgressCannotFinishNavigation() {
        val slow = BackProgressSpring()
        val capped = BackProgressSpring()
        assertEquals(capped.step(.96, .033), slow.step(.96, 1.0), .0000001)
        assertEquals(.96, dampedBackTarget(1.0), .0000001)
        assertEquals(.96, dampedBackTarget(4.0), .0000001)
        assertEquals(0.0, dampedBackTarget(-1.0), 0.0)
    }
    @Test fun layersMatchIndependentDartReferenceAtAllStageBoundaries() {
        val rows = javaClass.getResourceAsStream("/reference-back-layers.csv")!!.bufferedReader().readLines().drop(1)
        rows.forEach { row ->
            val expected = row.split(',').map(String::toDouble)
            val actual = backLayers(expected[0])
            val values = listOf(actual.foregroundAlpha, actual.foregroundXFraction, actual.backgroundAlpha, actual.backgroundXFraction, actual.maskAlpha)
            values.forEachIndexed { index, value -> assertEquals("p=${expected[0]}, field=$index", expected[index + 1], value.toDouble(), .000001) }
        }
    }
    @Test fun sixtyFramesMatchIndependentDartSpringTrace() {
        val spring = BackProgressSpring()
        val rows = javaClass.getResourceAsStream("/reference-back-spring.csv")!!.bufferedReader().readLines().drop(1)
        rows.forEach { row ->
            val expected = row.split(',').map(String::toDouble)
            spring.step(dampedBackTarget(.5), 1.0 / 60)
            assertEquals(expected[1], spring.position, .000000001)
            assertEquals(expected[2], spring.velocity, .000000001)
        }
    }
}
