package com.mycompany.arkitekt

import live.arkitekt.*

// A login that talks to no server, for tests that need clients to exist but never call them.

/** An alias pointing at a local port: nothing has to listen there. */
fun localAlias(port: Int) =
        Alias(id = "fake", host = "127.0.0.1", port = port, ssl = false, challenge = "ht")

/** A TokenManager that hands out a fixed token and never talks to a server. */
fun fixedTokenManager(token: String = "test-token"): TokenManager {
    val endpoint = FaktsEndpoint(
            name = "test",
            version = "0.1.0",
            base_url = "http://localhost/lok/f/",
            frontend_url = "http://localhost/",
            configure = "http://localhost/configure/{code}",
            device_authorization_endpoint = "http://localhost/lok/o/app-authorization/",
            token_endpoint = "http://localhost/lok/o/token/",
            protocol_version = "2"
    )
    val fakts = ActiveFakts(
            self = SelfFakt("test", Alias(host = "localhost", ssl = false, challenge = "ht")),
            instances = emptyMap()
    )
    val fresh = TokenResponse(
            access_token = token,
            client_id = "cid",
            refresh_token = "rt",
            expires_in = 3600,
            received_at = System.currentTimeMillis()
    )
    return TokenManager(FaktsClient(), endpoint, fresh, fakts) { _, _, _ -> }
}
