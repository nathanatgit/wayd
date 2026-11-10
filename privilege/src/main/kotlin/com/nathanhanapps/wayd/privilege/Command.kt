package com.nathanhanapps.wayd.privilege

/** New privileged operations must be fixed templates here, never caller-supplied command strings. */
sealed interface Command {
    val argv: List<String>
    data object ListPackages : Command {
        override val argv = listOf("pm", "list", "packages", "--user", "current")
    }
}

object PackageInventory {
    fun parse(output: String): Set<String> {
        if (output.length > 600 * 1024) return emptySet()
        return output.lineSequence().filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it == "android" || PackageNames.isValid(it) }.take(5000).toSet()
    }
}
