package live.arkitekt.imagej

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import live.arkitekt.imagej.mikro.graphql.CreateCoordinateSystemMutation
import live.arkitekt.imagej.mikro.graphql.DatasetLensesQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.arkitekt.Rekuest
import live.arkitekt.Unlok
import net.imagej.ImageJ
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plugin against a real mikro: what a schema file and a fake cannot say.
 *
 * One small image is uploaded through the plugin's own write path, and everything else is read
 * off the server's answer about it: the reference an action is handed (the object and mikro's
 * descriptors of it, in one query), and a constrained `@Action` passing and failing on those
 * descriptors.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MikroIntegrationTest {

    private var stack: MikroStack? = null

    /** Every request the mikro client makes. */
    private val requests = AtomicInteger(0)

    private lateinit var services: Services
    private lateinit var context: live.arkitekt.Context

    /** The uploaded image: c, t, z, y, x. */
    private val shape = intArrayOf(2, 1, 3, 8, 8)
    private lateinit var datasetId: String

    @BeforeAll
    fun start() {
        assumeTrue(MikroStack.available(), "Docker is not available: the integration test needs it")
        val started = MikroStack.start()
        stack = started

        val tokens = fixedTokenManager(MikroStack.TOKEN)
        val alias = localAlias(started.mikroPort)
        val counting =
                object : HttpInterceptor {
                    override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
                        requests.incrementAndGet()
                        return chain.proceed(request)
                    }
                }
        val mikro = Mikro(alias, tokens, listOf(counting))
        val datalayer = Datalayer(localAlias(started.storagePort), mikro)

        System.setProperty("java.awt.headless", "true")
        val ij = ImageJ()
        val unlok = Unlok(alias, tokens)
        val rekuest = Rekuest(alias, tokens)
        services = Services(mikro, datalayer, unlok, rekuest, ij.ui(), ij.dataset(), ij.imageDisplay())
        // What a Runtime builds at login, by hand: the app's declared services, by their keys.
        context = live.arkitekt.Context(unlok, rekuest, tokens, mapOf(mikroService to mikro, datalayerService to datalayer))

        val array = ucar.ma2.Array.factory(ucar.ma2.DataType.UBYTE, shape)
        datasetId = runBlocking { uploadArray(services, array, "integration") }.id
    }

    @AfterAll
    fun stop() {
        stack?.stop()
    }

    @Test
    fun `an uploaded image comes back as a reference with mikro's descriptors, and reads back`() = runBlocking {
        val dataset = DatasetRef.expand(context, datasetId)
        assertNotNull(dataset.dataset, "the expand loads the dataset itself")
        // mikro's own count of what was uploaded: c, t, z, y, x.
        val described = assertNotNull(dataset.descriptors)
        assertEquals(3, described[N_SPACE_AXES]?.jsonPrimitive?.int, "descriptors: $described")
        assertEquals(1, described[N_CHANNEL_AXES]?.jsonPrimitive?.int)
        assertEquals(2, described[N_CHANNELS]?.jsonPrimitive?.int)

        // The dataset's whole lens, as an action taking a lens is handed it.
        val lensId =
                services.mikro.getClient()
                        .query(DatasetLensesQuery(datasetId, Optional.present(false)))
                        .execute().dataOrThrow().arrayDataset.lenses.first().id
        val lens = ArrayLensRef.expand(context, lensId)
        assertEquals(3, lens.descriptors?.get(N_SPACE_AXES)?.jsonPrimitive?.int)

        // And its pixels read back through the lens: the box that was written.
        val view = lensViewOf(assertNotNull(lens.lens))
        val image = loadLensView(services, services.datalayer.requestAccess(view.storeId), view)
        assertEquals(shape.fold(1L) { size, extent -> size * extent }, (0 until image.numDimensions()).fold(1L) { size, d -> size * image.dimension(d) })
    }

    @Test
    fun `an upload carries its anchors and its calibration, and mikro takes both`() = runBlocking {
        val calibrated = ImageMetadata(
                title = "calibrated",
                sourceFile = "/data/cells.tif",
                axes = listOf(
                        AxisCalibration("c", present = true),
                        AxisCalibration("t", present = false),
                        AxisCalibration("z", present = true, unit = "micron", scale = 0.5),
                        AxisCalibration("y", present = true, unit = "micron", scale = 0.1),
                        AxisCalibration("x", present = true, unit = "micron", scale = 0.1)
                ),
                channels = listOf(ChannelInfo(0, name = "DAPI", color = "#0000ff"), ChannelInfo(1)),
                info = "Info as ImageJ keeps it"
        )
        // The anchors go with the create: a refusal of them throws here.
        val created = uploadArray(services, ucar.ma2.Array.factory(ucar.ma2.DataType.UBYTE, shape), "calibrated", calibrated)
        // uploadArray only reports a refused calibration, so send both kinds again and see them
        // taken: the SCALE it sends, and the AFFINE an image with an origin sends.
        val client = services.mikro.getClient()
        val scale = assertNotNull(calibrationOf(created.id, "calibrated (scale)", calibrated))
        assertNotNull(client.mutation(CreateCoordinateSystemMutation(scale)).execute().dataOrThrow().createCoordinateSystem.id)
        val shifted = calibrated.copy(axes = calibrated.axes.map { if (it.name == "x") it.copy(origin = 5.0) else it })
        val affine = assertNotNull(calibrationOf(created.id, "calibrated (affine)", shifted))
        assertNotNull(client.mutation(CreateCoordinateSystemMutation(affine)).execute().dataOrThrow().createCoordinateSystem.id)
    }

    @Test
    fun `a constrained action passes and fails on what mikro says of the object`() = runBlocking {
        val argument = mapOf("dataset" to JsonPrimitive(datasetId))

        val returned = TakeVolumeAction.declare("1").handler(context, argument)["return0"]!!.jsonObject
        assertEquals("@mikro/arraydataset", returned["__identifier"]!!.jsonPrimitive.content)
        assertEquals(datasetId, returned["object"]!!.jsonPrimitive.content)

        val refused = kotlin.runCatching { TakePlaneAction.declare("1").handler(context, argument) }.exceptionOrNull()
        assertEquals(
                "'dataset' is not what the port requires: @mikro/n_space_axes EQUALS 2 (actual 3)",
                refused?.message
        )
    }

    @Test
    fun `a constrained call costs one query, not one more to describe`() = runBlocking {
        val before = requests.get()
        // Expanded, tested against its Requires, tested against its Provides, shrunk.
        TakeVolumeAction.declare("1").handler(context, mapOf("dataset" to JsonPrimitive(datasetId)))
        assertEquals(1, requests.get() - before)
        assertTrue(requests.get() > 0)
    }
}
