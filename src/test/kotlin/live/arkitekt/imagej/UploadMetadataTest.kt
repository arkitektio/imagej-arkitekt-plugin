package live.arkitekt.imagej

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.arkitekt.imagej.mikro.graphql.type.CreatableTransformKind
import live.arkitekt.imagej.mikro.graphql.type.PlacementValidity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an upload says of the image: units, the calibration edge, the anchors. */
class UploadMetadataTest {

    private fun image(vararg axes: AxisCalibration, channels: List<ChannelInfo> = emptyList()) =
            ImageMetadata(
                    title = "cells.tif",
                    axes = UPLOAD_AXIS_NAMES.map { name -> axes.firstOrNull { it.name == name } ?: AxisCalibration(name, present = false) },
                    channels = channels
            )

    @Test
    fun `ImageJ's unit spellings become mikro's, and pixels are no unit`() {
        for (micron in listOf("µm", "μm", "um", "micron", "microns")) assertEquals("micrometer", lengthUnit(micron))
        assertEquals("nanometer", lengthUnit("nm"))
        assertNull(lengthUnit("pixel"))
        assertNull(lengthUnit(""))
        assertEquals("second", timeUnit("sec"))
        assertEquals("minute", timeUnit("min"))
        assertNull(timeUnit("frame"))
    }

    @Test
    fun `a calibrated stack is a SCALE edge, every upload axis with a factor`() {
        val input = calibrationOf(
                "7", "cells",
                image(
                        AxisCalibration("x", true, "micron", 0.1),
                        AxisCalibration("y", true, "micron", 0.1),
                        AxisCalibration("z", true, "micron", 0.5),
                        AxisCalibration("t", true, "sec", 2.0)
                )
        )!!
        assertEquals(listOf("c", "t", "z", "y", "x"), input.axes.map { it.name })
        assertEquals(listOf("dimensionless", "second", "micrometer", "micrometer", "micrometer"), input.axes.map { it.unit.getOrNull() })
        val edge = input.registrations.getOrNull()!!.single()
        assertEquals("7", edge.dataset.getOrNull())
        assertEquals(PlacementValidity.INFERRED, edge.validity.getOrNull())
        val transform = edge.transform.getOrNull()!!
        assertEquals(CreatableTransformKind.SCALE, transform.kind)
        assertEquals(listOf(1.0, 2.0, 0.5, 0.1, 0.1), transform.scale.getOrNull())
    }

    @Test
    fun `an offset makes it one AFFINE, and an uncalibrated image gets no calibration`() {
        val shifted = calibrationOf(
                "7", "cells",
                image(AxisCalibration("x", true, "um", 0.2, origin = 10.0), AxisCalibration("y", true, "um", 0.2))
        )!!
        val affine = shifted.registrations.getOrNull()!!.single().transform.getOrNull()!!
        assertEquals(CreatableTransformKind.AFFINE, affine.kind)
        // Row x: the scale on the diagonal, the offset last.
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.2, 10.0), affine.affine.getOrNull()!![4])

        // No frame interval: time is "a.u." (mikro refuses "dimensionless" on a TIME axis).
        assertEquals("a.u.", shifted.axes.first { it.name == "t" }.unit.getOrNull())

        assertNull(calibrationOf("7", "cells", image(AxisCalibration("x", true, "pixel"), AxisCalibration("y", true, "pixel"))))
    }

    @Test
    fun `the metadata rides one anchor at voxel 0, and each named channel gets a label at c`() {
        val anchors = anchorsOf(
                image(
                        AxisCalibration("x", true, "um", 0.1),
                        channels = listOf(ChannelInfo(0, name = "DAPI", color = "#0000ff"), ChannelInfo(1))
                )
        )
        assertEquals(2, anchors.size)
        assertEquals(listOf("c", "t", "z", "y", "x"), anchors[0].axisAnchors.map { it.axis })
        val json = Json.parseToJsonElement(anchors[0].omeMetadata.getOrNull()!!.metadataString).jsonObject
        assertEquals("cells.tif", json["title"]!!.jsonPrimitive.content)
        assertEquals("c", anchors[1].axisAnchors.single().axis)
        assertEquals("DAPI", anchors[1].label.getOrNull()!!.label)
    }

    @Test
    fun `a slice label names a channel only when it holds a name`() {
        assertEquals("DAPI", channelNameFromLabel("c:1/3 - DAPI"))
        assertEquals("GFP", channelNameFromLabel("GFP\nmore lines"))
        assertNull(channelNameFromLabel("c:1/3"))
        assertNull(channelNameFromLabel("cells.tif"))
        assertNull(channelNameFromLabel(null))
    }
}
