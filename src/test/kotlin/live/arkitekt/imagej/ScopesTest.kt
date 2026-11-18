package live.arkitekt.imagej

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plugin asks at login for what it does. Mikro's schema says what each root field takes
 * (`@auth(required_scopes: […])`), so the scopes in the code are held to the schema and the
 * operations checked in beside it. An operation added without its scope is refused only at
 * run time, in Fiji, with a token that was never asked to carry it.
 */
class ScopesTest {

    @Test
    fun `the plugin asks for what its mikro operations need`() {
        assertEquals(requiredScopes(File("src/main/graphql/mikro")), MIKRO_SCOPES.sorted())
    }
}

/**
 * What the operations in [folder] (`*.graphql`) need, by the schema beside them (`*.graphqls`):
 * the `required_scopes` of every root field an operation selects, sorted.
 */
private fun requiredScopes(folder: File): List<String> {
    val files = folder.listFiles().orEmpty()
    val schema = files.filter { it.extension == "graphqls" }.joinToString("\n") { it.readText() }
    val operation = Regex("""(?m)^(?:query|mutation|subscription)\s+\w+[^{]*\{\s*(\w+)""")
    val selected = files.filter { it.extension == "graphql" }.flatMap { operation.findAll(it.readText()).map { m -> m.groupValues[1] } }
    return selected
            .flatMap { field ->
                val declared =
                        Regex("""(?m)^  ${Regex.escape(field)}\b[^\n]*@auth\(([^)]*)\)""").find(schema)
                                ?: error("$field has no @auth in the schema of ${folder.name}")
                val scopes = Regex("""required_scopes:\s*\[([^\]]*)]""").find(declared.groupValues[1])?.groupValues?.get(1).orEmpty()
                Regex(""""([^"]+)"""").findAll(scopes).map { it.groupValues[1] }.toList()
            }
            .distinct()
            .sorted()
}
