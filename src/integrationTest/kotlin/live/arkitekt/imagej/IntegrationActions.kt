package live.arkitekt.imagej

import live.arkitekt.Action
import live.arkitekt.DescriptorOperator
import live.arkitekt.Provides
import live.arkitekt.Requires

// Constrained actions for the integration test: specs as type aliases of a mikro structure, as
// an app would write them. Compiled with arkitekt-kotlin's processor (kspIntegrationTest), so
// `TakeVolumeAction` and `TakePlaneAction` are its real output.

/** A dataset with three space axes, as mikro itself counts them. */
@Requires(N_SPACE_AXES, DescriptorOperator.EQUALS, "3")
@Provides(N_SPACE_AXES, DescriptorOperator.EQUALS, "3")
typealias VolumeDataset = DatasetRef

/** A dataset with two. */
@Requires(N_SPACE_AXES, DescriptorOperator.EQUALS, "2")
typealias PlaneDataset = DatasetRef

/** Hand a volume back. */
@Action
fun takeVolume(dataset: VolumeDataset): VolumeDataset = dataset

/** Take a plane, and say which. */
@Action
fun takePlane(dataset: PlaneDataset): String = dataset.id
