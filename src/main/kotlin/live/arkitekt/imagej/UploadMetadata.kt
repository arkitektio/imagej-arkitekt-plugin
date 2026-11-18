package live.arkitekt.imagej

import com.apollographql.apollo.api.Optional
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import live.arkitekt.imagej.mikro.graphql.type.AxisAnchorInput
import live.arkitekt.imagej.mikro.graphql.type.AxisType
import live.arkitekt.imagej.mikro.graphql.type.CoordinateAnchorInput
import live.arkitekt.imagej.mikro.graphql.type.CreatableTransformKind
import live.arkitekt.imagej.mikro.graphql.type.CreateCoordinateSystemInput
import live.arkitekt.imagej.mikro.graphql.type.LabelInput
import live.arkitekt.imagej.mikro.graphql.type.OmeMetadataInput
import live.arkitekt.imagej.mikro.graphql.type.PhysicalAxisInput
import live.arkitekt.imagej.mikro.graphql.type.PlacementValidity
import live.arkitekt.imagej.mikro.graphql.type.RegistrationPathInput
import live.arkitekt.imagej.mikro.graphql.type.TransformInput
import net.imagej.axis.Axes
import net.imagej.axis.CalibratedAxis
import net.imglib2.display.ColorTable
import net.imagej.ImgPlus

// =========================================================================================
// What an uploaded image says of itself, carried to mikro the way its Python converters carry
// it (converter_ome_dataset.py):
//
//   - its physical size, as a coordinate system with an INFERRED edge from the dataset's pixel
//     grid (what `dataset.calibrate(...)` writes; a later calibration by hand outranks it);
//   - everything true of the whole image on ONE anchor at voxel (0, ..., 0), as an OME
//     metadata JSON object (title, source file, per-axis calibration, channel colours and
//     display ranges, ImageJ's Info and properties);
//   - each named channel's name as a label on its own anchor, {c: i}.
//
// The upload axes are always c, t, z, y, x (an axis the image lacks has size 1), so
// everything here is in that order.
// =========================================================================================

/** The axes an upload writes, in order. */
val UPLOAD_AXIS_NAMES = listOf("c", "t", "z", "y", "x")

/** One upload axis as ImageJ calibrates it: [unit] as ImageJ spells it, [present] if the image has it. */
data class AxisCalibration(
        val name: String,
        val present: Boolean,
        val unit: String? = null,
        val scale: Double = 1.0,
        val origin: Double = 0.0
)

/** A channel: its name (when one is known), the colour it is drawn in, and its display range. */
data class ChannelInfo(
        val index: Int,
        val name: String? = null,
        val color: String? = null,
        val displayMin: Double? = null,
        val displayMax: Double? = null
)

/** Everything an upload carries about the image besides its pixels. */
data class ImageMetadata(
        val title: String?,
        val sourceFile: String? = null,
        val axes: List<AxisCalibration> = UPLOAD_AXIS_NAMES.map { AxisCalibration(it, present = false) },
        val channels: List<ChannelInfo> = emptyList(),
        val info: String? = null,
        val properties: Map<String, String> = emptyMap()
) {
    fun axis(name: String): AxisCalibration = axes.first { it.name == name }
}

// ---- Units ------------------------------------------------------------------------------------

/** An ImageJ length unit as mikro (pint) names it; null when uncalibrated or unknown. */
fun lengthUnit(raw: String?): String? =
        when (raw?.trim()?.lowercase()?.replace('μ', 'µ')) {
            "µm", "um", "micron", "microns", "micrometer", "micrometers", "micrometre", "micrometres" -> "micrometer"
            "nm", "nanometer", "nanometers", "nanometre", "nanometres" -> "nanometer"
            "mm", "millimeter", "millimeters", "millimetre", "millimetres" -> "millimeter"
            "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> "centimeter"
            "m", "meter", "meters", "metre", "metres" -> "meter"
            "pm", "picometer", "picometers" -> "picometer"
            "å", "a", "angstrom", "angstroms" -> "angstrom"
            "inch", "inches", "in" -> "inch"
            else -> null
        }

/** An ImageJ time unit as mikro (pint) names it; null when uncalibrated or unknown. */
fun timeUnit(raw: String?): String? =
        when (raw?.trim()?.lowercase()?.replace('μ', 'µ')) {
            "s", "sec", "secs", "second", "seconds" -> "second"
            "ms", "msec", "millisecond", "milliseconds" -> "millisecond"
            "µs", "us", "microsecond", "microseconds" -> "microsecond"
            "ns", "nanosecond", "nanoseconds" -> "nanosecond"
            "min", "mins", "minute", "minutes" -> "minute"
            "h", "hr", "hrs", "hour", "hours" -> "hour"
            else -> null
        }

// ---- Reading an image ---------------------------------------------------------------------

/** The ImageJ2 axis type each upload axis comes from. */
private val AXIS_TYPES = mapOf("c" to Axes.CHANNEL, "t" to Axes.TIME, "z" to Axes.Z, "y" to Axes.Y, "x" to Axes.X)

/**
 * The image's own calibration, per upload axis: the ImageJ2 axes carry unit, scale and offset
 * whichever world (ImageJ 1 or 2) the image came from.
 */
fun axesOf(imgPlus: ImgPlus<*>): List<AxisCalibration> =
        UPLOAD_AXIS_NAMES.map { name ->
            val d = (0 until imgPlus.numDimensions()).firstOrNull { imgPlus.axis(it).type() == AXIS_TYPES.getValue(name) }
            if (d == null) AxisCalibration(name, present = false)
            else {
                val axis: CalibratedAxis = imgPlus.axis(d)
                val origin = axis.calibratedValue(0.0)
                val scale = axis.calibratedValue(1.0) - origin
                AxisCalibration(
                        name,
                        present = true,
                        unit = axis.unit()?.takeIf { it.isNotBlank() },
                        scale = if (scale.isFinite() && scale != 0.0) scale else 1.0,
                        origin = if (origin.isFinite()) origin else 0.0
                )
            }
        }

/** Channel colours and display ranges as ImageJ2 holds them (no names: ImageJ2 has none). */
fun channelsOf(imgPlus: ImgPlus<*>): List<ChannelInfo> {
    val c = (0 until imgPlus.numDimensions()).firstOrNull { imgPlus.axis(it).type() == Axes.CHANNEL }
    val count = if (c == null) 1 else imgPlus.dimension(c).toInt()
    return (0 until count).map { i ->
        val table = if (i < imgPlus.colorTableCount) imgPlus.getColorTable(i) else null
        ChannelInfo(
                index = i,
                color = table?.let(::topColor),
                displayMin = imgPlus.getChannelMinimum(i).takeIf { it.isFinite() },
                displayMax = imgPlus.getChannelMaximum(i).takeIf { it.isFinite() }
        )
    }
}

/** The colour a lookup table ends at (what the channel's brightest value is drawn in), as #rrggbb. */
private fun topColor(table: ColorTable): String {
    val last = table.length - 1
    val r = table.get(ColorTable.RED, last).coerceIn(0, 255)
    val g = table.get(ColorTable.GREEN, last).coerceIn(0, 255)
    val b = if (table.componentCount > 2) table.get(ColorTable.BLUE, last).coerceIn(0, 255) else 0
    return "#%02x%02x%02x".format(r, g, b)
}

/**
 * A channel name out of an ImageJ 1 slice label, when it holds one. Bio-Formats and many
 * readers write the channel's name there; a bare position ("c:1/3") or a file name is no name.
 */
fun channelNameFromLabel(label: String?): String? {
    val first = label?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // "c:1/3 - DAPI" -> "DAPI"; "c:1/3" alone names nothing.
    val named = Regex("""^(?:[czt]:\d+/\d+\s*)+(?:-\s*)?(.*)$""").matchEntire(first)?.groupValues?.get(1)?.trim() ?: first
    if (named.isEmpty()) return null
    if (Regex("""(?i).*\.(tif|tiff|png|jpg|czi|lif|nd2|ome\.tif|ims|lsm|oib|oif)$""").matches(named)) return null
    return named.take(200)
}

// ---- To mikro ----------------------------------------------------------------------------------

/**
 * The physical space the image is calibrated in, and the INFERRED edge from the uploaded
 * dataset into it; null when the image has no length calibration (y and x must both have one),
 * which is then left to whoever knows it.
 *
 * As mikro's `calibrate()` writes it: every upload axis gets a factor (an axis the image lacks,
 * 1.0), the channel axis is dimensionless, an uncalibrated time axis is "a.u.", and
 * an offset turns the SCALE into one AFFINE (an edge is exactly one kind).
 */
fun calibrationOf(datasetId: String, title: String, metadata: ImageMetadata): CreateCoordinateSystemInput? {
    val y = metadata.axis("y")
    val x = metadata.axis("x")
    val yUnit = lengthUnit(y.unit) ?: return null
    val xUnit = lengthUnit(x.unit) ?: return null
    val z = metadata.axis("z")
    val t = metadata.axis("t")
    val zUnit = lengthUnit(z.unit) ?: xUnit
    val tUnit = timeUnit(t.unit)

    // The factors for c, t, z, y, x: what one step along each axis is.
    // mikro wants a TIME axis measured in [time]; one ImageJ gives no interval is "a.u." (its
    // spelling for a genuinely arbitrary unit), never "dimensionless", which it refuses there.
    val units = listOf("dimensionless", tUnit ?: "a.u.", zUnit, yUnit, xUnit)
    val scales = listOf(
            1.0,
            if (tUnit != null) t.scale else 1.0,
            // A step along z in x's unit only when ImageJ gave z no unit of its own (ImageJ 1
            // then means the same unit); a z unit mikro does not know is no length at all.
            if (z.present && (lengthUnit(z.unit) != null || z.unit.isNullOrBlank())) z.scale else 1.0,
            y.scale,
            x.scale
    )
    val origins = listOf(
            0.0,
            if (tUnit != null) t.origin else 0.0,
            if (z.present) z.origin else 0.0,
            y.origin,
            x.origin
    )
    val types = listOf(AxisType.CHANNEL, AxisType.TIME, AxisType.SPACE, AxisType.SPACE, AxisType.SPACE)

    val transform =
            if (origins.all { it == 0.0 }) TransformInput(kind = CreatableTransformKind.SCALE, scale = Optional.present(scales))
            else TransformInput(
                    kind = CreatableTransformKind.AFFINE,
                    // M x (N+1), rows outermost: the scale on the diagonal, the offset last.
                    affine = Optional.present(
                            scales.indices.map { row ->
                                scales.indices.map { col -> if (col == row) scales[row] else 0.0 } + origins[row]
                            }
                    )
            )

    val space = "$title (physical)"
    return CreateCoordinateSystemInput(
            name = space,
            axes = UPLOAD_AXIS_NAMES.indices.map { i ->
                PhysicalAxisInput(name = UPLOAD_AXIS_NAMES[i], type = types[i], unit = Optional.present(units[i]))
            },
            registrations = Optional.present(
                    listOf(
                            RegistrationPathInput(
                                    dataset = Optional.present(datasetId),
                                    name = Optional.present("$title -> $space"),
                                    transform = Optional.present(transform),
                                    // Read off the file by an importer: a calibration by hand outranks it.
                                    validity = Optional.present(PlacementValidity.INFERRED)
                            )
                    )
            )
    )
}

/** The metadata JSON the origin anchor carries (`omeMetadata`, a JSON object). */
fun metadataJson(metadata: ImageMetadata): JsonObject = buildJsonObject {
    put("source", "imagej")
    metadata.title?.let { put("title", it) }
    metadata.sourceFile?.let { put("source_file", it) }
    putJsonArray("axes") {
        for (axis in metadata.axes) {
            add(buildJsonObject {
                put("name", axis.name)
                put("present", axis.present)
                axis.unit?.let { put("unit", it) }
                put("scale", axis.scale)
                put("origin", axis.origin)
            })
        }
    }
    putJsonArray("channels") {
        for (channel in metadata.channels) {
            add(buildJsonObject {
                put("index", channel.index)
                channel.name?.let { put("name", it) }
                channel.color?.let { put("color", it) }
                channel.displayMin?.let { put("display_min", it) }
                channel.displayMax?.let { put("display_max", it) }
            })
        }
    }
    metadata.info?.let { put("imagej_info", it) }
    if (metadata.properties.isNotEmpty()) {
        putJsonObject("imagej_properties") { metadata.properties.forEach { (k, v) -> put(k, v) } }
    }
}

/** The anchors an upload is created with: the image's metadata at voxel 0, each named channel at {c: i}. */
fun anchorsOf(metadata: ImageMetadata): List<CoordinateAnchorInput> {
    val origin = CoordinateAnchorInput(
            axisAnchors = UPLOAD_AXIS_NAMES.map { AxisAnchorInput(axis = it, value = 0) },
            omeMetadata = Optional.present(OmeMetadataInput(metadataString = metadataJson(metadata).toString()))
    )
    val labels = metadata.channels.mapNotNull { channel ->
        channel.name?.let {
            CoordinateAnchorInput(
                    axisAnchors = listOf(AxisAnchorInput(axis = "c", value = channel.index)),
                    label = Optional.present(LabelInput(label = it))
            )
        }
    }
    return listOf(origin) + labels
}

// ImageJ's Info text can be a whole Bio-Formats metadata dump: kept, but not without bound.
internal const val MAX_INFO_CHARS = 256 * 1024
