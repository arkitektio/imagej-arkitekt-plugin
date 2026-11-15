package live.arkitekt.imagej

import live.arkitekt.App
import net.imagej.DatasetService
import net.imagej.display.ImageDisplayService
import org.scijava.ui.UIService

/**
 * The ImageJ app: what this plugin is to a deployment, and the actions it offers there.
 *
 * A declaration only (arkitekt-kotlin's [App]). The actions are the `@Action` functions in
 * Arkitekt.kt: to add one, write the function there and name its generated declaration here.
 * It is registered on the next login.
 *
 * The ImageJ services are the host the actions run in. They are provided to the actions
 * together with the login's clients, as [Services].
 */
fun imagejApp(
        uiService: UIService,
        datasetService: DatasetService,
        imageDisplayService: ImageDisplayService
): App =
        // rekuest is every app's; mikro and the datalayer are this one's.
        App("imagej", "0.1.0")
                .service(mikroService)
                .service(datalayerService)
                .provide { context ->
                    Services(
                            context[mikroService],
                            context[datalayerService],
                            context.unlok,
                            context.rekuest,
                            uiService,
                            datasetService,
                            imageDisplayService
                    )
                }
                .actions(
                        UploadActiveImageAction,
                        ShowLensAction,
                        ShowDatasetAction,
                        AnnotateLensAction,
                        AnnotateInFijiAction,
                        RunImageToImageMacroAction
                )
