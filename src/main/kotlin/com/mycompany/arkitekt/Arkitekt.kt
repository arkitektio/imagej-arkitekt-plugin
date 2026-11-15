package com.mycompany.arkitekt

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import live.arkitekt.*
import com.mycompany.mikro.graphql.CreateAnnotationCollectionMutation
import com.mycompany.mikro.graphql.GetAnnotationCollectionQuery
import com.mycompany.mikro.graphql.CreateAnnotationMutation
import com.mycompany.mikro.graphql.UpdateAnnotationMutation
import com.mycompany.mikro.graphql.CreateArrayDatasetMutation
import com.mycompany.mikro.graphql.GetArrayDatasetQuery
import com.mycompany.mikro.graphql.GetLensQuery
import com.mycompany.mikro.graphql.FinishZarrUploadMutation
import com.mycompany.mikro.graphql.RequestZarrAccessMutation
import com.mycompany.mikro.graphql.RequestZarrUploadMutation
import com.mycompany.mikro.graphql.type.AxisInput
import com.mycompany.mikro.graphql.type.AxisType
import com.mycompany.mikro.graphql.type.CoordinateInput
import com.mycompany.mikro.graphql.type.CreatableTransformKind
import com.mycompany.mikro.graphql.type.AnnotationKind
import com.mycompany.mikro.graphql.type.CreateAnnotationCollectionInput
import com.mycompany.mikro.graphql.type.CreateAnnotationInput
import com.mycompany.mikro.graphql.type.DerivationSourceKind
import com.mycompany.mikro.graphql.type.DerivedFromInput
import com.mycompany.mikro.graphql.type.TransformInput
import com.mycompany.mikro.graphql.type.UpdateAnnotationInput
import com.mycompany.mikro.graphql.type.CreateArrayDatasetInput
import com.mycompany.mikro.graphql.type.FinishZarrUploadInput
import com.mycompany.mikro.graphql.type.RequestZarrAccessInput
import com.mycompany.mikro.graphql.type.RequestZarrUploadInput
import com.mycompany.rekuest.graphql.type.*
import dev.zarr.zarrjava.store.S3Store
import dev.zarr.zarrjava.store.StoreHandle
import dev.zarr.zarrjava.v3.Array
import dev.zarr.zarrjava.v3.DataType
import ucar.ma2.DataType as UcarDataType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.imagej.DatasetService
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imagej.axis.CalibratedAxis
import net.imagej.axis.DefaultLinearAxis
import net.imagej.display.ImageDisplay
import net.imagej.display.ImageDisplayService
import net.imagej.display.OverlayService
import net.imglib2.RandomAccess
import net.imglib2.type.numeric.RealType
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.scijava.ui.UIService
import java.util.*
import java.util.prefs.Preferences
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import dev.zarr.zarrjava.v3.Group;
import net.imagej.ImageJ
import net.imagej.Dataset
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.integer.ByteType
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.ShortType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import ucar.ma2.*
import org.scijava.Context
import org.scijava.convert.ConvertService
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import net.imglib2.Cursor
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.logging.Logger
import com.apollographql.apollo.api.Operation
import net.imglib2.type.numeric.real.DoubleType

class DatalayerStore(store: S3Store, storeId: String) {
    val store = store
    val storeId = storeId
}

class Datalayer(alias: Alias, mikro: Mikro) {
    private var mikro = mikro
    private var alias = alias

    // Request a fresh Zarr upload grant. Unlike the old `requestUpload`, the server
    // now assigns the object `key` (and `store` id) itself — we no longer pass one in.
    suspend fun requestStore(): DatalayerStore {

        val client = mikro.getClient()

        val response =
                client.mutation(RequestZarrUploadMutation(RequestZarrUploadInput()))
                        .execute()

        val accessKeyId = response.data?.requestZarrUpload?.accessKey
        val secretAccessKey = response.data?.requestZarrUpload?.secretKey
        val sessionToken = response.data?.requestZarrUpload?.sessionToken
        val bucketName = response.data?.requestZarrUpload?.bucket
        val key = response.data?.requestZarrUpload?.key
        val store = response.data?.requestZarrUpload?.store

        if (accessKeyId == null ||
                        secretAccessKey == null ||
                        sessionToken == null ||
                        bucketName == null ||
                        key == null ||
                        store == null
        ) {
            throw Exception("Failed to retrieve S3 credentials")
        }

        val sessionCredentials =
                AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)

        val s3Client =
                S3Client.builder()
                        .endpointOverride(URI.create(alias.to_http_path("")))
                        .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials))
                        .region(Region.US_EAST_1)
                        .forcePathStyle(true)
                        .build()

        return DatalayerStore(S3Store(s3Client, bucketName, key), store)
    }

    // Mark a Zarr upload as complete so the server validates and finalizes the store.
    // Must be called after the array bytes are written and before the store id is used.
    suspend fun finishStore(storeId: String) {
        val client = mikro.getClient()
        client.mutation(FinishZarrUploadMutation(FinishZarrUploadInput(storeId = storeId)))
                .execute()
    }


    suspend fun requestAccess(storeId: String): DatalayerStore {

        val client = mikro.getClient()

        val response =
            client.mutation(
                RequestZarrAccessMutation(
                    RequestZarrAccessInput(storeId = storeId)
                )
            )
                .execute()

        val accessKeyId = response.data?.requestZarrAccess?.accessKey
        val secretAccessKey = response.data?.requestZarrAccess?.secretKey
        val sessionToken = response.data?.requestZarrAccess?.sessionToken
        val bucketName = response.data?.requestZarrAccess?.bucket
        val key = response.data?.requestZarrAccess?.key

        if (accessKeyId == null ||
            secretAccessKey == null ||
            sessionToken == null ||
            bucketName == null ||
            key == null
        ) {
            throw Exception("Failed to retrieve S3 credentials")
        }

        val sessionCredentials =
            AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)

        val s3Client =
            S3Client.builder()
                .endpointOverride(URI.create(alias.to_http_path("")))
                .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build()

        return DatalayerStore(S3Store(s3Client, bucketName, key), storeId)

    }





}

class Mikro(alias: Alias, tokens: TokenManager) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor { tokens.accessToken() })
                .addHttpInterceptor(LogInterceptor("mikro"))
                .addInterceptor(AuthRetryInterceptor(tokens))
                .addInterceptor(ErrorLoggingInterceptor())
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

/** Mikro, as an app declares it: `App(…).service(mikroService)`, then `context[mikroService]`. */
val mikroService = Service("mikro", "live.arkitekt.mikro") { Mikro(it.alias, it.tokens) }

/** The datalayer (S3), whose credentials mikro issues: declared after [mikroService]. */
val datalayerService = Service("datalayer", "live.arkitekt.s3") { Datalayer(it.alias, it[mikroService]) }

/**
 * What this plugin's handlers work with: the deployment's clients (from the login's
 * [Context]) and the ImageJ they run in.
 */
class Services(
        public val mikro: Mikro,
        public val datalayer: Datalayer,
        public val unlok: Unlok,
        public val rekuest: Rekuest,
        public val uiService: UIService,
        public val datasetService: DatasetService,
        public var imageDisplayService: ImageDisplayService
)

// A rekuest STRUCTURE arg arrives either as a bare id string or, more commonly, as the shrunk
// wire form {"__identifier": "@mikro/lens", "object": "<id>"} (see rekuest-next
// structures/serialization/postman.py:ashrink_arg). This returns the id for both shapes.
fun structureArgId(value: JsonElement?): String {
    if (value == null) throw IllegalArgumentException("Missing structure argument")
    return when (value) {
        is JsonObject ->
                value["object"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Structure arg missing 'object': $value")
        else -> value.jsonPrimitive.content
    }
}

// Builds the shrunk wire form rekuest's aexpand_return requires for a STRUCTURE return — a bare
// id string is rejected there, it must be the tagged {"__identifier", "object"} dict.
fun structureReturn(identifier: String, id: String): JsonElement = buildJsonObject {
    put("__identifier", identifier)
    put("object", id)
}

fun <T : RealType<T>> imgPlusToCTZYXUcarArray(imgPlus: ImgPlus<T>): ucar.ma2.Array {
    val numDimensions = imgPlus.numDimensions()
    val axisTypes = (0 until numDimensions).map { imgPlus.axis(it).type() }

    // Final axis order: c=0, t=1, z=2, x=3, y=4
    var cSize = 1
    var tSize = 1
    var zSize = 1
    var xSize = 1
    var ySize = 1

    var cDim: Int? = null
    var tDim: Int? = null
    var zDim: Int? = null
    var xDim: Int? = null
    var yDim: Int? = null

    // Identify sizes and mapping for each axis
    for (d in 0 until numDimensions) {
        val sizeD = imgPlus.dimension(d)
        val axisType = axisTypes[d]
        when {
            axisType == Axes.CHANNEL -> {
                cSize = sizeD.toInt()
                cDim = d
            }
            axisType == Axes.TIME -> {
                tSize = sizeD.toInt()
                tDim = d
            }
            axisType == Axes.Z -> {
                zSize = sizeD.toInt()
                zDim = d
            }
            axisType == Axes.X -> {
                xSize = sizeD.toInt()
                xDim = d
            }
            axisType == Axes.Y -> {
                ySize = sizeD.toInt()
                yDim = d
            }
        }
    }

    // finalToImgDim maps the canonical axes (c,t,z,y,x) to their ImgPlus dimension index.
    val finalToImgDim = arrayOf(cDim, tDim, zDim, yDim, xDim)

    val ra: RandomAccess<T> = imgPlus.randomAccess()

    // Preserve the source dtype rather than force-casting to UINT32. The ucar dtype is chosen
    // from the ImgLib2 pixel type; `realDouble` carries the correct (unsigned) magnitude for
    // every integer type up to 32-bit, so a single read path fills any branch.
    val sample = imgPlus.firstElement()
    val ucarType: UcarDataType =
            when (sample) {
                is UnsignedByteType -> UcarDataType.UBYTE
                is UnsignedShortType -> UcarDataType.USHORT
                is UnsignedIntType -> UcarDataType.UINT
                is ByteType -> UcarDataType.BYTE
                is ShortType -> UcarDataType.SHORT
                is IntType -> UcarDataType.INT
                is FloatType -> UcarDataType.FLOAT
                is DoubleType -> UcarDataType.DOUBLE
                else -> UcarDataType.FLOAT
            }

    // Canonical c,t,z,y,x. The ucar IndexIterator walks row-major (x fastest), which matches
    // the loop nesting below, so values land in the right cells.
    val shape = intArrayOf(cSize, tSize, zSize, ySize, xSize)
    val ucarArray: ucar.ma2.Array = ucar.ma2.Array.factory(ucarType, shape)
    val iter = ucarArray.indexIterator

    val position = LongArray(numDimensions)
    for (c in 0 until cSize) {
        for (t in 0 until tSize) {
            for (z in 0 until zSize) {
                for (y in 0 until ySize) {
                    for (x in 0 until xSize) {
                        java.util.Arrays.fill(position, 0L)
                        finalToImgDim[0]?.let { position[it] = c.toLong() }
                        finalToImgDim[1]?.let { position[it] = t.toLong() }
                        finalToImgDim[2]?.let { position[it] = z.toLong() }
                        finalToImgDim[3]?.let { position[it] = y.toLong() }
                        finalToImgDim[4]?.let { position[it] = x.toLong() }

                        ra.setPosition(position)
                        val v = ra.get().realDouble
                        when (ucarType) {
                            UcarDataType.UBYTE, UcarDataType.BYTE -> iter.setByteNext(v.toInt().toByte())
                            UcarDataType.USHORT, UcarDataType.SHORT -> iter.setShortNext(v.toInt().toShort())
                            UcarDataType.UINT, UcarDataType.INT -> iter.setIntNext(v.toLong().toInt())
                            UcarDataType.FLOAT -> iter.setFloatNext(v.toFloat())
                            else -> iter.setDoubleNext(v)
                        }
                    }
                }
            }
        }
    }

    return ucarArray
}


// The axes the upload path writes, matching the `withDimensionNames("c","t","z","y","x")` in
// `uploadArray`. mikro v2 imposes no canonical order — `axes` IS the order — so this states what
// the writer does rather than a convention the server would otherwise assume.
private val UPLOAD_AXES = listOf(
        AxisInput(name = "c", type = AxisType.CHANNEL),
        AxisInput(name = "t", type = AxisType.TIME),
        AxisInput(name = "z", type = AxisType.SPACE),
        AxisInput(name = "y", type = AxisType.SPACE),
        AxisInput(name = "x", type = AxisType.SPACE),
)

// The store's own dimension names, when it records them, must agree with the axes the coordinate
// system declares — they are two statements of the same fact and the server builds one from the
// other. Disagreement means the selection would be applied to the wrong dimensions.
private fun assertStoreAgrees(axisNames: List<String>, dimensionNames: List<String?>?) {
    val declared = dimensionNames?.filterNotNull() ?: return
    if (declared.size != dimensionNames.size) return  // partially named store: nothing to compare
    require(declared == axisNames) {
        "The store's dimension names $declared disagree with the source's axes $axisNames"
    }
}

// Build a readable selection from a Lens. `Lens.renderAxes` is deprecated, so the render axes are
// inferred from the axis names and types together (`resolveRenderAxes`); `Lens.shape` cross-checks
// what we derived from the slices.
fun lensViewOf(lens: GetLensQuery.Lens): LensView {
    val axes = lens.coordinateSystem?.axes
            ?.sortedBy { it.order }
            ?.map { AxisSpec(it.name, it.type) }
            ?: throw IllegalStateException("Lens ${lens.id} has no coordinate system, so its axis types are unknown")

    require(axes.map { it.name } == lens.axisNames) {
        "Lens ${lens.id} reports axis names ${lens.axisNames} but its coordinate system declares ${axes.map { it.name }}"
    }

    val level0 = lens.dataset.dataArrays.firstOrNull { it.level == 0 }
            ?: throw IllegalStateException("Dataset ${lens.dataset.id} has no level-0 array to read")
    assertStoreAgrees(lens.axisNames, level0.store.dimensionNames)

    return buildLensView(
            storeId = level0.store.id,
            axes = axes,
            storeShape = level0.shape,
            slices = lens.slices.map { SliceSpec(it.axis, it.start, it.stop, it.step) },
            render = resolveRenderAxes(axes),
            name = lens.dataset.name,
            serverShape = lens.shape,
    )
}

// The same, for a whole ArrayDataset: no slices, and the same locally-inferred render axes.
suspend fun fetchDatasetView(services: Services, datasetId: String): LensView {
    val response = services.mikro.getClient().query(GetArrayDatasetQuery(id = datasetId)).execute()
    val dataset = response.dataOrThrow().arrayDataset

    val axes = dataset.intrinsicSystem?.axes
            ?.sortedBy { it.order }
            ?.map { AxisSpec(it.name, it.type) }
            ?: throw IllegalStateException("Dataset ${dataset.id} has no intrinsic coordinate system")

    val level0 = dataset.dataArrays.firstOrNull { it.level == 0 }
            ?: throw IllegalStateException("Dataset ${dataset.id} has no level-0 array to read")
    assertStoreAgrees(dataset.axisNames, level0.store.dimensionNames)

    return buildLensView(
            storeId = level0.store.id,
            axes = axes,
            storeShape = level0.shape,
            slices = emptyList(),
            render = resolveRenderAxes(axes),
            name = dataset.name,
            serverShape = null,
    )
}

// Read the box a [LensView] selects out of its zarr store and hand it to [buildDataset].
//
// Two functions rather than one so the interesting half — the permutation, the dtype dispatch
// and the axis labelling — is testable without S3 or a network.
fun loadLensView(services: Services, store: DatalayerStore, view: LensView): Dataset {
    val zarrArray = Array.open(store.store.resolve())

    // read(offset, shape) pulls out the [start, stop) box. It has no stride, so a stepped lens
    // is subsampled afterwards.
    val box: ucar.ma2.Array = zarrArray.read(view.offset, view.extent)

    return buildDataset(services, applyStride(box, view), view)
}

// Turn a read selection into an ImageJ Dataset with correctly named and typed axes.
//
// The permutation is the load-bearing part. ImgLib2 iterates dimension 0 fastest; ucar iterates
// its LAST dimension fastest. So to zip a flat ucar IndexIterator against a flat ImgLib2 Cursor
// the ucar array must be permuted into the reverse of the ImageJ dimension order. The previous
// version of this function skipped that and hard-coded c,t,z,y,x, which silently transposed the
// two slowest axes whenever an image had both channels > 1 and time > 1.
fun buildDataset(services: Services, array: ucar.ma2.Array, view: LensView): Dataset {
    val datasetService = services.datasetService

    val order = imageJDimOrder(view)                       // indices into view.axes, ImageJ order
    val dims = order.map { array.shape[it].toLong() }.toLongArray()

    // ucar's last dimension varies fastest, ImgLib2's first does — hence the reverse.
    val permuted = array.permute(order.reversedArray()).copy()

    // Label every dimension. An axis the renderer does not name keeps its own name rather than
    // being dropped, which is what lets a MICROTIME or SPECTRUM axis reach the viewer.
    val axisTypes = order.map { imageJAxisTypeFor(view.axes[it].name, view.render) }.toTypedArray()
    val name = view.name

    return when (permuted.dataType) {
        UcarDataType.UBYTE, UcarDataType.BYTE -> {
            // uint8 reads back as ucar UBYTE; mask to keep the unsigned 0..255 magnitude.
            val img = ArrayImgs.unsignedBytes(*dims)
            val cursor: Cursor<UnsignedByteType> = img.cursor()
            val iterator = permuted.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.byteNext.toInt() and 0xFF)
            }
            datasetService.create(ImgPlus(img, name, axisTypes))
        }
        UcarDataType.SHORT, UcarDataType.USHORT -> {
            val img = ArrayImgs.unsignedShorts(*dims)
            val cursor: Cursor<UnsignedShortType> = img.cursor()
            val iterator = permuted.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.shortNext.toInt() and 0xFFFF)
            }
            datasetService.create(ImgPlus(img, name, axisTypes))
        }
        UcarDataType.UINT -> {
            val img = ArrayImgs.unsignedInts(*dims)
            val cursor: Cursor<UnsignedIntType> = img.cursor()
            val iterator = permuted.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.intNext.toLong() and 0xFFFFFFFFL)
            }
            datasetService.create(ImgPlus(img, name, axisTypes))
        }
        UcarDataType.INT -> {
            val img = ArrayImgs.ints(*dims)
            val cursor: Cursor<IntType> = img.cursor()
            val iterator = permuted.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.intNext)
            }
            datasetService.create(ImgPlus(img, name, axisTypes))
        }
        UcarDataType.FLOAT -> {
            val img = ArrayImgs.floats(*dims)
            val cursor: Cursor<FloatType> = img.cursor()
            val iterator = permuted.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.floatNext)
            }
            datasetService.create(ImgPlus(img, name, axisTypes))
        }
        UcarDataType.DOUBLE -> {
            val img = ArrayImgs.doubles(*dims)
            val cursor: Cursor<DoubleType> = img.cursor()
            val iterator = permuted.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.doubleNext)
            }
            datasetService.create(ImgPlus(img, name, axisTypes))
        }
        else -> throw IllegalArgumentException("Unsupported data type: ${permuted.dataType}")
    }
}

// Run an arbitrary ImageJ (IJ1) macro against a Dataset and return the transformed Dataset.
//
// The macro engine (`ij.IJ.runMacro`) is IJ1-only, so this needs the ImageJ legacy layer on the
// classpath — Fiji provides it, and `./gradlew run` / `macroSmokeTest` get it from the dedicated
// `ij1Runtime` configuration (build.gradle.kts: imagej-legacy is compileOnly so it never reaches
// runtimeClasspath and is never bundled). The conversion Dataset<->ImagePlus
// is done through the SciJava ConvertService, whose converters imagej-legacy registers at runtime.
//
// The image is made the IJ1 "current image" via WindowManager.setTempCurrentImage — this avoids
// opening/closing an AWT window (so it is safe to call off the EDT, which is where the agent
// handler runs) and is the standard pattern for programmatic image-to-image macros, which operate
// in place on the current image. If the macro replaces the current image, we read that back instead.
fun runMacroOnDataset(services: Services, dataset: Dataset, macro: String): Dataset {
    val context = services.datasetService.context()
    val convertService = context.getService(ConvertService::class.java)
            ?: throw IllegalStateException("ConvertService is unavailable in this context.")

    val imp = convertService.convert(dataset, ImagePlus::class.java)
            ?: throw IllegalStateException(
                    "Could not convert the image to an IJ1 ImagePlus. Running macros requires the " +
                            "ImageJ legacy layer (imagej-legacy) on the classpath — Fiji provides it, " +
                            "as does the `ij1Runtime` configuration under `./gradlew run`."
            )

    val result: ImagePlus =
            try {
                WindowManager.setTempCurrentImage(imp)
                IJ.runMacro(macro)
                // Prefer whatever the macro left as the current image (a macro may replace it);
                // fall back to the in-place-modified input.
                WindowManager.getCurrentImage() ?: imp
            } finally {
                WindowManager.setTempCurrentImage(null)
            }

    return convertService.convert(result, Dataset::class.java)
            ?: throw IllegalStateException("Could not convert the macro result back to a Dataset.")
}


// ---- The actions' handlers --------------------------------------------------------------
//
// Plain functions of (Services, args): ImageJApp.kt declares them as the app's actions.

// Port of mikro_next `rechunk` (mikro_next/utils.py): aim for ~20 MB chunks given the
// dtype's byte width. Shape and chunks are in canonical c,t,z,y,x order: c is always 1,
// x/y are kept whole (capped at 2048), then z and t are sized to fit the byte budget.
fun generateChunkShape(inarrayShape: IntArray, itemSize: Int): IntArray {
    if (inarrayShape.size != 5) {
        throw IllegalArgumentException(
                "Input array shape must have exactly 5 dimensions: c, t, z, y, x"
        )
    }

    val cs = inarrayShape[0]
    val ts = inarrayShape[1]
    val zs = inarrayShape[2]
    val ys = inarrayShape[3]
    val xs = inarrayShape[4]

    // Don't bother rechunking small arrays — store them as a single chunk.
    val totalElements = 1L * cs * ts * zs * ys * xs
    if (totalElements < 1L * 2048 * 2048) {
        return inarrayShape.copyOf()
    }

    val chunkSizeInBytes = 20_000_000L
    val x = if (xs > 2048) 2048 else xs
    val y = if (ys > 2048) 2048 else ys

    val bestZ = Math.ceil(chunkSizeInBytes.toDouble() / (x.toLong() * y * itemSize)).toInt()
    val z = if (bestZ < zs) bestZ else zs

    val bestT = Math.ceil(chunkSizeInBytes.toDouble() / (x.toLong() * y * z * itemSize)).toInt()
    val t = if (bestT < ts) bestT else ts

    return intArrayOf(1, t, z, y, x)
}

suspend fun uploadArray(
        services: Services,
        inarray: ucar.ma2.Array,
        name: String
): CreateArrayDatasetMutation.CreateArrayDataset {

    val s3Client = services.datalayer.requestStore()

    // Preserve the source dtype: map the incoming ucar dtype to the matching zarr v3 dtype
    // (and its byte width, which drives the ~20 MB chunk heuristic).
    val (zarrType, itemSize) =
            when (inarray.dataType) {
                UcarDataType.UBYTE -> DataType.UINT8 to 1
                UcarDataType.BYTE -> DataType.INT8 to 1
                UcarDataType.USHORT -> DataType.UINT16 to 2
                UcarDataType.SHORT -> DataType.INT16 to 2
                UcarDataType.UINT -> DataType.UINT32 to 4
                UcarDataType.INT -> DataType.INT32 to 4
                UcarDataType.FLOAT -> DataType.FLOAT32 to 4
                UcarDataType.DOUBLE -> DataType.FLOAT64 to 8
                else -> throw IllegalArgumentException("Unsupported dtype for upload: ${inarray.dataType}")
            }

    val array =
            Array.create(
                    s3Client.store.resolve(),
                    Array.metadataBuilder()
                            .withShape(*inarray.shape.map { i -> i.toLong() }.toLongArray())
                            .withDimensionNames("c", "t", "z", "y", "x")
                            .withDataType(zarrType)
                            .withChunkShape(
                                    *generateChunkShape(
                                            inarray.shape.map { i -> i.toInt() }.toIntArray(),
                                            itemSize
                                    )
                            )
                            .withFillValue(0)
                            .withCodecs { it.withBlosc() }
                            .build()!!
            )

    array.write(listOf(0L, 0L, 0L, 0L, 0L).toLongArray(), inarray)

    // Finalize the upload before registering it (the server validates the store).
    services.datalayer.finishStore(s3Client.storeId)

    // The writer above declares its dimension names as c,t,z,y,x; `axes` must say the same
    // thing with types attached. mikro v2 imposes no canonical order — `axes` IS the order —
    // so this states the order the writer used rather than a convention the server assumes.
    val mutation =
            CreateArrayDatasetMutation(
                    CreateArrayDatasetInput(
                            data = s3Client.storeId,
                            scales = emptyList(),
                            name = name,
                            axes = UPLOAD_AXES,
                    )
            )

    val client = services.mikro.getClient()

    val response = client.mutation(mutation).execute()

    println("Response: ${response.data}")
    return response.dataOrThrow().createArrayDataset
}

suspend fun uploadActiveImage(services: Services, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

    services.imageDisplayService.imageDisplays.forEach { d -> println(d) }

    var active = services.imageDisplayService.getActiveDataset(services.imageDisplayService.imageDisplays[0])

    var name = args.get("name")!!.jsonPrimitive.content

    var array = imgPlusToCTZYXUcarArray(active.imgPlus)

    var dataset = uploadArray(services, array, name)

    return mapOf(Pair("dataset", structureReturn("@mikro/arraydataset", dataset.id)))
}


// Show a mikro Lens: the per-axis selection over a dataset that the server renders as a
// layer. Only the box the lens selects is read — a lens cropped to four z-planes shows four
// planes, not the whole stack.
suspend fun showLens(services: Services, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

    val lensId = structureArgId(args["lens"])

    val response = services.mikro.getClient().query(GetLensQuery(id = lensId)).execute()
    val lens = response.dataOrThrow().lens

    val view = lensViewOf(lens)
    val store = services.datalayer.requestAccess(view.storeId)
    val dataset = loadLensView(services, store, view)

    services.imageDisplayService.createImageDisplay(dataset)

    return mapOf(Pair("lens", structureReturn("@mikro/lens", lensId)))
}

// The unsliced sibling of `showLens`: a whole ArrayDataset, read through the same path as a
// lens that selects everything.
suspend fun showDataset(services: Services, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

    val datasetId = structureArgId(args["dataset"])

    val view = fetchDatasetView(services, datasetId)
    val store = services.datalayer.requestAccess(view.storeId)
    val dataset = loadLensView(services, store, view)

    services.imageDisplayService.createImageDisplay(dataset)

    return mapOf(Pair("dataset", structureReturn("@mikro/arraydataset", datasetId)))
}

// How often the drawing surface is re-read. Fast enough to feel live to someone drawing, slow
// enough that a session costs a handful of cheap reads per second and no server traffic unless
// something actually changed.
private val POLL_INTERVAL_MS = 500L

// The slice a shape sits on, for every axis it does not span.
//
// An IJ1 ROI banked in the ROI Manager remembers the slice it was drawn on, and the viewer has
// usually moved on since — so the ROI's own answer wins where it has one. IJ1 positions are
// 1-based with 0 meaning "unset"; mikro's are plain indices, hence the -1.
//
// Axes nothing can answer for are left out rather than pinned to a guessed 0: an unpinned axis
// reads server-side as "the shape spans it", which is the honest answer when we do not know.
private fun positionOf(item: DrawnShape, display: ImageDisplay, view: LensView): Map<String, Int> {
    val fromDisplay = view.axes
            .filter { it.name != view.render.x && it.name != view.render.y }
            .mapNotNull { axis ->
                runCatching { axis.name to display.getIntPosition(imageJAxisTypeFor(axis.name, view.render)) }
                        .getOrNull()
            }
            .toMap()

    return fromDisplay + ij1Pins(item.ij1Position, view.render)
}

// Open a lens and save every ROI the user draws into a fresh annotation collection, as they
// draw it.
//
// "Live" here means each shape is persisted the moment it appears — it is a side effect against
// mikro, not a stream back to rekuest, because this agent client emits exactly one YIELD per
// ASSIGN (Agent.kt) and mikro has no annotation subscription. The action therefore runs long and
// returns the collection id only at the end; every shape is already saved by then.
//
// The collection is minted up front with an IDENTITY edge onto the LENS. That is what PLACES it:
// omit the transform and the edge is UNMAPPABLE — lineage only, no geometry — and every mutation
// still succeeds, so the failure is invisible from here. Naming the lens rather than the dataset
// means the lens' own edge back to its dataset carries any crop for free.
suspend fun annotateLens(services: Services, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

    val lensId = structureArgId(args["lens"])

    val lens = services.mikro.getClient().query(GetLensQuery(id = lensId)).execute().dataOrThrow().lens
    val view = lensViewOf(lens)
    val store = services.datalayer.requestAccess(view.storeId)
    val dataset = loadLensView(services, store, view)

    val collectionId = createAnnotationCollection(services, lensId, lens.dataset.name, view)

    runAnnotationSession(services, view, dataset, collectionId, emptyList(), "annotate_lens")

    return mapOf(Pair("collection", structureReturn("@mikro/annotationcollection", collectionId)))
}

// The two-way sibling: open a lens, draw the annotations it already has back into the viewer,
// and keep syncing from there — an edit to a pulled shape updates the annotation it came from,
// a new drawing creates one.
//
// WHY THE COLLECTION IS AN ARGUMENT and not something we look up: an annotation's `vectors` are
// positional in ITS collection's declared axis order, and a lens can carry any number of
// collections drawn over it by any number of clients. "The annotations of this lens" is
// therefore not a well-defined set to decode — so the caller names one collection, whose axes
// travel with it and are cross-checked against the lens (`collectionRenderAxes`) before a
// single shape is drawn. With no collection given this is exactly `annotate_lens`: a fresh
// collection and nothing to pull.
//
// The pull is ONE-SHOT, at open. mikro has no annotation subscription (`core/subscriptions/` is
// just `files.py`), so "keep the viewer in step with the server" would mean re-querying every
// poll and diffing — which cannot distinguish a shape deleted remotely from one this session
// has not pushed yet. Pull once, push continuously.
suspend fun annotateInFiji(services: Services, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

    val lensId = structureArgId(args["lens"])

    val lens = services.mikro.getClient().query(GetLensQuery(id = lensId)).execute().dataOrThrow().lens
    val view = lensViewOf(lens)
    val store = services.datalayer.requestAccess(view.storeId)
    val dataset = loadLensView(services, store, view)

    // A nullable STRUCTURE port arrives as JsonNull when the caller leaves it empty, which
    // `structureArgId` would happily read as the literal string "null".
    val collectionArg = args["collection"]?.takeIf { it !is JsonNull }

    val collectionId: String
    val prefill: List<PrefillShape>
    if (collectionArg == null) {
        collectionId = createAnnotationCollection(services, lensId, lens.dataset.name, view)
        prefill = emptyList()
    } else {
        collectionId = structureArgId(collectionArg)
        prefill = loadPrefill(services, collectionId, view)
    }

    runAnnotationSession(services, view, dataset, collectionId, prefill, "annotate_in_fiji")

    return mapOf(Pair("collection", structureReturn("@mikro/annotationcollection", collectionId)))
}

// Mint the drawing surface: a collection whose axes ARE the lens' axes, in array order. Two
// things ride on that — an IDENTITY edge needs matching rank and names, and `vectors` are
// positional in the collection's declared axis order, so this is also what fixes the layout of
// everything the session sends.
private suspend fun createAnnotationCollection(
        services: Services,
        lensId: String,
        datasetName: String,
        view: LensView,
): String = services.mikro.getClient().mutation(
        CreateAnnotationCollectionMutation(
                CreateAnnotationCollectionInput(
                        name = "$datasetName — ImageJ",
                        axes = view.axes.map { AxisInput(name = it.name, type = it.type) },
                        derivedFrom = Optional.present(
                                listOf(
                                        DerivedFromInput(
                                                kind = DerivationSourceKind.LENS,
                                                lens = Optional.present(lensId),
                                                transform = Optional.present(
                                                        TransformInput(kind = CreatableTransformKind.IDENTITY)
                                                ),
                                        )
                                )
                        ),
                )
        )
).execute().dataOrThrow().createAnnotationCollection.id

// Read an existing collection's shapes, ready to be drawn.
//
// The axes come from the COLLECTION, not from the lens: they are what the vectors are indexed
// by, and a collection minted elsewhere may order them differently. `collectionRenderAxes`
// refuses the pair outright if the two disagree about which axis is x or y — decoding anyway
// would place every shape transposed, and nothing downstream would notice.
private suspend fun loadPrefill(services: Services, collectionId: String, view: LensView): List<PrefillShape> {
    val collection = services.mikro.getClient()
            .query(GetAnnotationCollectionQuery(id = collectionId))
            .execute().dataOrThrow().annotationCollection

    val axes = collection.coordinateSystem.axes.sortedBy { it.order }.map { AxisSpec(it.name, it.type) }
    val render = collectionRenderAxes(axes, view.render)

    val shapes = collection.annotations.mapNotNull { annotation ->
        prefillShapeFor(
                RemoteAnnotation(
                        id = annotation.id.toString(),
                        kind = annotation.kind,
                        vectors = annotation.vectors,
                        coordinates = annotation.coordinates.map { it.name to it.value },
                ),
                axes,
                render,
        )
    }

    // Undecodable shapes are counted out loud: "pulled 3" when the collection held 7 reads as
    // success. Volumetric kinds are the usual reason — a 2D drawing surface cannot show them.
    val undecodable = collection.annotations.size - shapes.size
    println("annotate_in_fiji: ${shapes.size} shape(s) to draw from collection $collectionId" +
            if (undecodable > 0) " ($undecodable not drawable here)" else "")

    return shapes
}

// The drawing session itself: open the image, put [prefill] on it, then poll the drawing surface
// and push what changed until the window closes or the task is cancelled.
private suspend fun runAnnotationSession(
        services: Services,
        view: LensView,
        dataset: Dataset,
        collectionId: String,
        prefill: List<PrefillShape>,
        label: String,
) {
    val display = withContext(Dispatchers.Main) { services.imageDisplayService.createImageDisplay(dataset) }
    val overlayService = services.datasetService.context().getService(OverlayService::class.java)

    // Object identity, not geometry: ImageJ returns the same Overlay/Roi instance each poll, so
    // an edit is "same object, new geometry" (update) and a new drawing is a new object
    // (create). A geometry key would make every edit a duplicate.
    val saved = IdentityHashMap<Any, String>()
    val lastGeometry = IdentityHashMap<Any, List<List<Double>>>()
    // The kind the SERVER stored, for a pulled shape. A round trip is not kind-preserving —
    // an ij.gui.OvalRoi reads back as ELLIPSE whether it came from a CIRCLE or an ELLIPSE — and
    // editing a shape's geometry is not the user saying "make this a different kind".
    val pulledKind = IdentityHashMap<Any, AnnotationKind>()

    // Everything already on screen when we started — notably the ROI Manager, which is a global
    // singleton that outlives a run. Without this, a second session would re-save the first
    // one's shapes into its new collection.
    //
    // ORDER IS LOAD-BEARING: the baseline is taken BEFORE the prefill goes on screen. Snapshot
    // it afterwards and every pulled shape lands in `ignore`, so the poll never sees it again —
    // the user's edits to pulled shapes would silently never be pushed, and the feature would
    // look two-way while being one-way.
    val baseline = withContext(Dispatchers.Main) { drawnBaseline(overlayService, display) }

    var prefillSeeded = 0
    if (prefill.isNotEmpty()) {
        val result = withContext(Dispatchers.Main) {
            drawShapes(overlayService, display, prefill, view.render)
        }
        val byId = prefill.associateBy { it.id }
        for (installed in result.installed) {
            saved[installed.source] = installed.annotationId
            byId[installed.annotationId]?.let { pulledKind[installed.source] = it.shape.kind }
        }
        prefillSeeded = result.installed.size
        println("$label: drew ${result.installed.size} stored annotation(s)" +
                if (result.skipped > 0) " (${result.skipped} the viewer cannot draw)" else "")
    }

    var created = 0
    try {
        // Runs until the user closes the window, or the server cancels the task. A closed
        // display is dropped from the service's list, which is the only close signal available
        // without subscribing to events — but that list is only consulted AFTER a first poll,
        // because a display that has not registered yet would otherwise end the session
        // immediately and report success having saved nothing.
        var polled = false
        while (!polled || withContext(Dispatchers.Main) { services.imageDisplayService.imageDisplays.contains(display) }) {
            val drawn = withContext(Dispatchers.Main) { readDrawnShapes(overlayService, display, baseline) }

            // The one check that distinguishes a working prefill from a silently broken one.
            // Both ways it can break are invisible otherwise: IJ1 clones on add (so a
            // mis-keyed identity matches nothing) and `Ij1Rois.readAll` drops any ROI whose
            // owning ImagePlus is not the current image (so a prefill banked before the legacy
            // layer activated this display is read back for a different picture). Either way
            // the shapes are on screen and edits to them are pushed as NEW annotations, which
            // reads as "the sync duplicates everything" rather than as a seeding failure.
            if (!polled && prefillSeeded > 0) {
                val matched = drawn.count { saved.containsKey(it.source) }
                println("$label: first poll matched $matched of $prefillSeeded pulled shape(s)" +
                        if (matched < prefillSeeded) " — the rest will be re-created as new annotations" else "")
            }
            polled = true

            for (item in drawn) {
                val spec = runCatching {
                    annotationSpecFor(item.shape, view.axes, view.render, positionOf(item, display, view))
                }.getOrNull() ?: continue

                val existing = saved[item.source]

                // A pulled shape on its first sighting: record what it reads back AS, and send
                // nothing. The round trip is lossy — doubles through IJ1's float polygons, and
                // an IJ2 overlay that cannot carry a pin at all — so comparing against the
                // server's own vectors would fire a spurious update for every pulled shape,
                // overwriting the very coordinates and pins we just read.
                if (existing != null && !lastGeometry.containsKey(item.source)) {
                    lastGeometry[item.source] = spec.vectors
                    continue
                }

                // A failed mutation must not end the drawing session — this action is meant to
                // run for as long as someone is drawing, and one transient error would otherwise
                // propagate out of the handler and be reported CRITICAL.
                runCatching {
                if (existing == null) {
                    val createdAnnotation = services.mikro.getClient().mutation(
                            CreateAnnotationMutation(
                                    CreateAnnotationInput(
                                            kind = spec.kind,
                                            vectors = spec.vectors,
                                            collection = Optional.present(collectionId),
                                            coordinates = Optional.present(
                                                    spec.coordinates.map { CoordinateInput(it.first, it.second) }
                                            ),
                                    )
                            )
                    ).execute().dataOrThrow().createAnnotation
                    // Annotation.id is the UUID scalar, which Apollo types as Any.
                    saved[item.source] = createdAnnotation.id.toString()
                    lastGeometry[item.source] = spec.vectors
                    created++
                } else if (lastGeometry[item.source] != spec.vectors) {
                    services.mikro.getClient().mutation(
                            UpdateAnnotationMutation(
                                    UpdateAnnotationInput(
                                            id = existing,
                                            kind = Optional.present(pulledKind[item.source] ?: spec.kind),
                                            vectors = Optional.present(spec.vectors),
                                            coordinates = Optional.present(
                                                    spec.coordinates.map { CoordinateInput(it.first, it.second) }
                                            ),
                                    )
                            )
                    ).execute()
                    lastGeometry[item.source] = spec.vectors
                }
                }.onFailure { println("$label: could not save a ${spec.kind}: ${it.message}") }
            }

            // A cancellable suspension point: CANCEL/INTERRUPT are cooperative Job.cancel(), so
            // the loop must never block.
            delay(POLL_INTERVAL_MS)
        }
    } finally {
        // Cancellation lands here too. Every shape is already persisted, so there is nothing to
        // flush — this only reports what the session managed to save.
        println("$label: $created new annotation(s), ${saved.size - created} synced, in collection $collectionId")
    }
}

// Download an image, run an arbitrary ImageJ macro over it (image in -> image out), and upload
// the result as a new @mikro/arraydataset. The macro sees the downloaded image as the IJ1 "current
// image" and typically transforms it in place (e.g. run("Gaussian Blur...", "sigma=2")).
suspend fun runImageToImageMacro(services: Services, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

    val imageId = structureArgId(args["dataset"])
    val macro = args["macro"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'macro' argument")
    val name = args["name"]?.jsonPrimitive?.contentOrNull ?: "Macro result"

    // 1. Download the input dataset (same access path as showDataset).
    val view = fetchDatasetView(services, imageId)
    val store = services.datalayer.requestAccess(view.storeId)
    val inputDataset = loadLensView(services, store, view)

    // 2. Run the macro (needs the IJ1 legacy layer; see runMacroOnDataset).
    val outputDataset = runMacroOnDataset(services, inputDataset, macro)

    // 3. Upload the transformed image as a new @mikro/arraydataset.
    val array = imgPlusToCTZYXUcarArray(outputDataset.imgPlus)
    val result = uploadArray(services, array, name)

    return mapOf(Pair("dataset", structureReturn("@mikro/arraydataset", result.id)))
}
