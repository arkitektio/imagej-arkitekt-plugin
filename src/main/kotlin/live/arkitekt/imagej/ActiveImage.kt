package live.arkitekt.imagej

import ij.CompositeImage
import ij.ImagePlus
import ij.WindowManager
import net.imagej.Dataset
import net.imagej.ImgPlus
import net.imagej.legacy.LegacyService
import net.imglib2.img.ImagePlusAdapter
import net.imglib2.type.numeric.real.FloatType

// The image a person is looking at, as an upload takes it.
//
// In Fiji that is ImageJ 1's current window: ImageJ 1's windows, menus and toolbar are the UI.
// Its pixels and calibration are read from the ImageJ 1 image itself, wrapped afresh: the
// ImageJ2 dataset imagej-legacy keeps beside it is brought up to date only around the commands
// imagej-legacy runs, and an agent's task is none of them, so a filter run or a pixel size set
// since might not be there yet. (An RGB image goes through ImageJ2's dataset, which holds it as
// three channels.) Without ImageJ 1 (a pure ImageJ2 UI) it is the active ImageJ2 display.

/** What an upload sends: the pixels as c, t, z, y, x, and what the image says of itself. */
class UploadSource(val array: ucar.ma2.Array, val metadata: ImageMetadata)

/** The image that is active in ImageJ, ready to upload. */
fun activeUpload(services: Services): UploadSource {
    val legacy = legacyService(services)
    val imp = if (legacy != null) WindowManager.getCurrentImage() else null
    if (imp != null && imp.type != ImagePlus.COLOR_RGB) {
        // The pixel type is the image's own (8, 16 or 32 bit): every one of them is a RealType,
        // which is all the conversion reads.
        @Suppress("UNCHECKED_CAST")
        val imgPlus = ImagePlusAdapter.wrapImgPlus<FloatType>(imp) as ImgPlus<FloatType>
        return UploadSource(imgPlusToCTZYXUcarArray(imgPlus), metadataOf(imgPlus, imp.title, imp))
    }
    val (dataset, behind) = activeDataset(services, legacy, imp)
    return UploadSource(imgPlusToCTZYXUcarArray(dataset.imgPlus), metadataOf(dataset.imgPlus, dataset.name, behind))
}

private fun activeDataset(services: Services, legacy: LegacyService?, imp: ImagePlus?): Pair<Dataset, ImagePlus?> {
    val displays = services.imageDisplayService
    if (legacy != null && imp != null) {
        val map = legacy.imageMap
        val display = map.lookupDisplay(imp) ?: map.registerLegacyImage(imp)
        displays.getActiveDataset(display)?.let { return it to imp }
    }
    val display = displays.activeImageDisplay ?: displays.imageDisplays.firstOrNull()
            ?: throw IllegalStateException("No image is open in ImageJ: open the one to upload first")
    val dataset = displays.getActiveDataset(display)
            ?: throw IllegalStateException("The active display shows no image to upload")
    val behind = legacy?.let { runCatching { it.imageMap.lookupImagePlus(display) }.getOrNull() }
    return dataset to behind
}

private fun legacyService(services: Services): LegacyService? =
        try {
            services.uiService.context().getService(LegacyService::class.java)
        } catch (_: NoClassDefFoundError) {
            null // no imagej-legacy: a pure ImageJ2 UI
        }

/**
 * What the image says of itself. With the ImageJ 1 image behind it ([imp]): its calibration,
 * title, source file, channel names and colours, Info and properties, all from ImageJ 1 as it
 * stands now. Without: the calibration and channel colours ImageJ2 holds.
 */
fun metadataOf(imgPlus: ImgPlus<*>, name: String?, imp: ImagePlus?): ImageMetadata {
    var channels = channelsOf(imgPlus)
    if (imp != null) {
        val names = channelNames(imp)
        val colors = channelColors(imp)
        channels = channels.map { c ->
            c.copy(name = names.getOrNull(c.index) ?: c.name, color = colors.getOrNull(c.index) ?: c.color)
        }
    }
    val file = imp?.originalFileInfo
    val sourceFile =
            file?.let { info ->
                info.url?.takeIf { it.isNotBlank() }
                        ?: info.fileName?.takeIf { it.isNotBlank() && it != "Untitled" }?.let { (info.directory ?: "") + it }
            } ?: imgPlus.source?.takeIf { it.isNotBlank() }
    return ImageMetadata(
            title = imp?.title ?: name,
            sourceFile = sourceFile,
            axes = imp?.let(::calibrationAxes) ?: axesOf(imgPlus),
            channels = channels,
            info = imp?.infoProperty?.takeIf { it.isNotBlank() }?.take(MAX_INFO_CHARS),
            properties = imp?.let(::imageProperties).orEmpty()
    )
}

/**
 * ImageJ 1's calibration, per upload axis (Image › Properties). An uncalibrated image ("pixel")
 * has no unit; an origin is in pixels there, so it is turned into the offset of voxel 0.
 */
private fun calibrationAxes(imp: ImagePlus): List<AxisCalibration> {
    val cal = imp.calibration
    val scaled = cal.scaled()
    fun unit(raw: String?) = if (scaled) raw?.takeIf { it.isNotBlank() } else null
    val timed = cal.frameInterval > 0
    return listOf(
            AxisCalibration("c", present = imp.nChannels > 1),
            AxisCalibration(
                    "t", present = imp.nFrames > 1,
                    unit = if (timed) cal.timeUnit else null,
                    scale = if (timed) cal.frameInterval else 1.0
            ),
            AxisCalibration("z", present = imp.nSlices > 1, unit = unit(cal.zUnit), scale = cal.pixelDepth, origin = -cal.zOrigin * cal.pixelDepth),
            AxisCalibration("y", present = true, unit = unit(cal.yUnit), scale = cal.pixelHeight, origin = -cal.yOrigin * cal.pixelHeight),
            AxisCalibration("x", present = true, unit = unit(cal.unit), scale = cal.pixelWidth, origin = -cal.xOrigin * cal.pixelWidth)
    )
}

/** Each channel's name, from the first plane's slice label; empty when the labels name nothing. */
private fun channelNames(imp: ImagePlus): List<String?> {
    val stack = imp.stack ?: return emptyList()
    val names = (1..imp.nChannels).map { c -> channelNameFromLabel(stack.getSliceLabel(imp.getStackIndex(c, 1, 1))) }
    // One label repeated on every channel is the image's, not a channel's.
    return if (names.size > 1 && names.filterNotNull().distinct().size == 1) names.map { null } else names
}

/** Each channel's colour, from a composite image's lookup tables. */
private fun channelColors(imp: ImagePlus): List<String?> {
    val composite = imp as? CompositeImage ?: return emptyList()
    return (1..imp.nChannels).map { c ->
        runCatching {
            val lut = composite.getChannelLut(c)
            "#%02x%02x%02x".format(lut.getRed(255), lut.getGreen(255), lut.getBlue(255))
        }.getOrNull()
    }
}

/** ImageJ 1's string properties (`setProp`), when the image has any. */
private fun imageProperties(imp: ImagePlus): Map<String, String> {
    val pairs = runCatching { imp.propertiesAsArray }.getOrNull() ?: return emptyMap()
    return (0 until pairs.size / 2).associate { pairs[2 * it] to pairs[2 * it + 1] }
}
