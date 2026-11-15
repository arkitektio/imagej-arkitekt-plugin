package com.mycompany.arkitekt

import com.apollographql.apollo.api.Optional
import com.mycompany.rekuest.graphql.type.ActionKind
import com.mycompany.rekuest.graphql.type.ArgPortInput
import com.mycompany.rekuest.graphql.type.DefinitionInput
import com.mycompany.rekuest.graphql.type.PortKind
import com.mycompany.rekuest.graphql.type.ReturnPortInput
import kotlinx.serialization.json.JsonElement

// The app pattern, as in arkitekt (Python) and arkirust: an App is a declaration, and a Runtime
// runs it.
//
//     val app = App("imagej", "0.1.0")
//             .requires("mikro", "live.arkitekt.mikro")
//             .action("show_lens", name = "Show Lens", ports = {
//                 arg("lens", PortKind.STRUCTURE, "The lens to show", identifier = "@mikro/lens")
//             }) { services, args -> … }
//
//     Runtime(app, …).login(url, …)
//
// Declaring does nothing: no login, no socket, no client. That makes an App something a test can
// build and an agent can be handed without a server.

/** What an action is run with: the app's [Services], and its arguments by port key. */
typealias Handler = suspend (Services, Map<String, JsonElement?>) -> Map<String, JsonElement?>

/** One action an app offers: what it takes and returns, and the function that does it. */
class Action(val definition: DefinitionInput, val handler: Handler) {
    val key: String get() = definition.key
}

/** The ports of an action, in the order they are declared. */
class Ports {
    internal val args = mutableListOf<ArgPortInput>()
    internal val returns = mutableListOf<ReturnPortInput>()

    /** An argument. [identifier] names the structure of a `STRUCTURE` port (`@mikro/lens`). */
    fun arg(
            key: String,
            kind: PortKind,
            description: String? = null,
            identifier: String? = null,
            nullable: Boolean? = false
    ) {
        args +=
                ArgPortInput(
                        key = key,
                        kind = kind,
                        identifier = Optional.presentIfNotNull(identifier),
                        description = Optional.presentIfNotNull(description),
                        nullable = Optional.presentIfNotNull(nullable)
                )
    }

    /** A return value. The handler's map must carry it under [key]. */
    fun returns(
            key: String,
            kind: PortKind,
            description: String? = null,
            identifier: String? = null,
            nullable: Boolean? = false
    ) {
        returns +=
                ReturnPortInput(
                        key = key,
                        kind = kind,
                        identifier = Optional.presentIfNotNull(identifier),
                        description = Optional.presentIfNotNull(description),
                        nullable = Optional.presentIfNotNull(nullable)
                )
    }
}

/**
 * An app: who it is, which services it needs, and the actions it offers.
 *
 * Pure declaration. A [Runtime] logs it in (its [identifier], [version], [scopes] and
 * [requirements] are the manifest it shows the deployment) and serves its [actions].
 */
class App(val identifier: String, val version: String) {
    var description: String? = null
        private set

    /** What the login asks for. */
    val scopes: MutableList<String> = mutableListOf("openid")

    /** The services the app needs, by the key its code knows them under. */
    val requirements: MutableList<Requirement> = mutableListOf()

    /** The actions, by key, in the order they were declared. */
    val actions: LinkedHashMap<String, Action> = LinkedHashMap()

    fun description(text: String): App = apply { description = text }

    /** Need a service: `requires("mikro", "live.arkitekt.mikro")`. The login fails without it. */
    fun requires(key: String, service: String): App = apply {
        requirements += Requirement(key = key, service = service)
    }

    /**
     * Offer an action. [key] is what the server assigns by, [name] and [description] what a
     * person reads; [ports] declares the arguments and returns, and [handler] does the work.
     */
    fun action(
            key: String,
            name: String,
            description: String? = null,
            ports: Ports.() -> Unit = {},
            handler: Handler
    ): App = apply {
        require(key !in actions) { "$identifier already declares an action '$key'" }
        val declared = Ports().apply(ports)
        actions[key] =
                Action(
                        DefinitionInput(
                                key = key,
                                version = version,
                                name = name,
                                description = Optional.presentIfNotNull(description),
                                args = Optional.present(declared.args),
                                returns = Optional.present(declared.returns),
                                kind = ActionKind.FUNCTION
                        ),
                        handler
                )
    }
}
