package com.mycompany.arkitekt

import live.arkitekt.App
import live.arkitekt.Handler
import live.arkitekt.rekuest.graphql.type.PortKind
import kotlinx.serialization.json.JsonElement
import net.imagej.DatasetService
import net.imagej.display.ImageDisplayService
import org.scijava.ui.UIService

private const val LENS = "@mikro/lens"
private const val DATASET = "@mikro/arraydataset"
private const val COLLECTION = "@mikro/annotationcollection"

/**
 * The ImageJ app: what this plugin is to a deployment, and the actions it offers there.
 *
 * A declaration only (arkitekt-kotlin's [App]). To add an action, write its handler (a function
 * of `(Services, args)`, with the others in Arkitekt.kt) and declare it here: it is registered
 * on the next login.
 *
 * The ImageJ services are the host the actions run in: each handler is handed them together
 * with the login's clients, as [Services].
 */
fun imagejApp(
        uiService: UIService,
        datasetService: DatasetService,
        imageDisplayService: ImageDisplayService
): App {
    fun inImageJ(
            handler: suspend (Services, Map<String, JsonElement?>) -> Map<String, JsonElement?>
    ): Handler = { context, args ->
        handler(
                Services(
                        context[mikroService],
                        context[datalayerService],
                        context.unlok,
                        context.rekuest,
                        uiService,
                        datasetService,
                        imageDisplayService
                ),
                args
        )
    }

    // rekuest is every app's; mikro and the datalayer are this one's.
    return App("imagej", "0.1.0")
                .service(mikroService)
                .service(datalayerService)
                .action(
                        "frage",
                        name = "Upload Image",
                        description = "Upload the currently active image in the viewer.",
                        ports = {
                            arg("name", PortKind.STRING, "How would you like to name the image?")
                            returns("dataset", PortKind.STRUCTURE, "The returned dataset", DATASET, nullable = null)
                        },
                        handler = inImageJ(::uploadActiveImage)
                )
                .action(
                        "show_lens",
                        name = "Show Lens",
                        description =
                                "Show a lens — a per-axis selection over a dataset — in the viewer. " +
                                        "Only the region the lens selects is read.",
                        ports = {
                            arg("lens", PortKind.STRUCTURE, "The lens to show", LENS)
                            returns("lens", PortKind.STRUCTURE, "The lens that was shown", LENS)
                        },
                        handler = inImageJ(::showLens)
                )
                .action(
                        "show_dataset",
                        name = "Show Dataset",
                        description = "Show a whole array dataset in the viewer.",
                        ports = {
                            arg("dataset", PortKind.STRUCTURE, "The dataset to show", DATASET)
                            returns("dataset", PortKind.STRUCTURE, "The dataset that was shown", DATASET)
                        },
                        handler = inImageJ(::showDataset)
                )
                .action(
                        "annotate_lens",
                        name = "Annotate Lens",
                        description =
                                "Open a lens in the viewer and save every ROI drawn on it into a new " +
                                        "annotation collection, as it is drawn. In Fiji, press `t` to bank a " +
                                        "selection into the ROI Manager — that is the signal a shape is " +
                                        "finished. Runs until the image window is closed or the task is " +
                                        "cancelled; a dropped connection ends the session (the shapes already " +
                                        "saved are kept, but a re-run starts a second collection).",
                        ports = {
                            arg("lens", PortKind.STRUCTURE, "The lens to open and annotate", LENS)
                            returns(
                                    "collection",
                                    PortKind.STRUCTURE,
                                    "The collection the drawn ROIs were saved into",
                                    COLLECTION
                            )
                        },
                        handler = inImageJ(::annotateLens)
                )
                .action(
                        "annotate_in_fiji",
                        name = "Annotate In Fiji",
                        description =
                                "Open a lens in Fiji and sync its ROIs with an annotation collection in " +
                                        "both directions: the collection's existing shapes are drawn into the " +
                                        "viewer on open, and from then on every ROI drawn or edited is saved " +
                                        "back — an edit to a pulled shape updates it rather than duplicating " +
                                        "it. Press `t` to bank a selection into the ROI Manager; that is the " +
                                        "signal a shape is finished. With no collection given, a fresh one is " +
                                        "created and there is nothing to pull. The pull happens once, at open: " +
                                        "shapes added on the server afterwards do not appear. Volumetric " +
                                        "shapes (cube, sphere, ellipsoid) cannot be drawn on a 2D surface and " +
                                        "are left alone. Runs until the image window is closed or the task is " +
                                        "cancelled.",
                        ports = {
                            arg("lens", PortKind.STRUCTURE, "The lens to open and annotate", LENS)
                            arg(
                                    "collection",
                                    PortKind.STRUCTURE,
                                    "The collection to sync with. Its own axes must agree with " +
                                            "the lens about which axis is x and which is y — its " +
                                            "vectors are indexed by them. Leave empty to start a " +
                                            "new collection.",
                                    COLLECTION,
                                    nullable = true
                            )
                            returns(
                                    "collection",
                                    PortKind.STRUCTURE,
                                    "The collection the ROIs were synced with",
                                    COLLECTION
                            )
                        },
                        handler = inImageJ(::annotateInFiji)
                )
                .action(
                        "run_image_to_image_macro",
                        name = "Run Image-To-Image Macro",
                        description =
                                "Run an arbitrary ImageJ macro over an image and return the result as a new image.",
                        ports = {
                            arg("dataset", PortKind.STRUCTURE, "The dataset to run the macro on", DATASET)
                            arg(
                                    "macro",
                                    PortKind.STRING,
                                    "The ImageJ macro to run. It operates on the image as the current image, " +
                                            "e.g. run(\"Gaussian Blur...\", \"sigma=2\")."
                            )
                            arg(
                                    "name",
                                    PortKind.STRING,
                                    "How would you like to name the resulting image?",
                                    nullable = true
                            )
                            returns(
                                    "dataset",
                                    PortKind.STRUCTURE,
                                    "The resulting dataset after running the macro",
                                    DATASET,
                                    nullable = null
                            )
                        },
                        handler = inImageJ(::runImageToImageMacro)
                )
}
