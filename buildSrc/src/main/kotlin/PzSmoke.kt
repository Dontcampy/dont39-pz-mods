package buildsrc.convention

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starts the game or dedicated server in [dir] the way its `ProjectZomboid64.json` does, with ZombieBuddy's
 * `exit_after_game_init`, and waits until it exits. [osVmArgs]: also the json's `windows` arguments for this Windows
 * major version (the client test leaves them out, as it always has).
 */
fun runPzUntilInit(
  dir: File,
  what: String,
  programArgs: List<String> = emptyList(),
  osVmArgs: Boolean = false,
  vmArg: (String) -> String = { it },
) {
  val launcher = JsonSlurper().parse(dir.resolve("ProjectZomboid64.json")) as Map<*, *>
  val osArgs = if (osVmArgs) {
    val major = System.getProperty("os.version").substringBefore('.')
    ((launcher["windows"] as? Map<*, *>)?.get(major) as? Map<*, *>)?.get("vmArgs") as? List<*> ?: emptyList<Any>()
  } else {
    emptyList<Any>()
  }
  val vmArgs = ((launcher["vmArgs"] as List<*>) + osArgs).map { it.toString() }.map {
    vmArg(if (it.startsWith("-agentlib:zbNative")) "$it,exit_after_game_init,verbosity=1" else it)
  }
  if (vmArgs.none { it.startsWith("-agentlib:zbNative") }) {
    throw GradleException("${dir.resolve("ProjectZomboid64.json")} has no -agentlib:zbNative argument; is ZombieBuddy installed?")
  }
  val classpath = (launcher["classpath"] as List<*>).joinToString(File.pathSeparator)
  val command = listOf(dir.resolve("jre64/bin/java.exe").path) + vmArgs +
    listOf("-cp", classpath, (launcher["mainClass"] as String).replace('/', '.')) + programArgs
  // the output also goes to the console log, which is what gets checked
  val process = ProcessBuilder(command).directory(dir).redirectOutput(ProcessBuilder.Redirect.DISCARD)
    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
  if (!process.waitFor(10, TimeUnit.MINUTES)) {
    process.destroyForcibly()
    throw GradleException("The $what did not exit within 10 minutes")
  }
}

/**
 * Fails unless Zabric and ZabricKotlin are fully in effect in [log]: no preload registration this launch (that
 * needs another run, named by [rerun]), Zabric's widening summary ok, no entrypoint problem, ZabricKotlin ready.
 */
fun checkPlatformLog(log: String, console: File, rerun: String) {
  val lines = log.lines()
  lines.firstOrNull { "[Zabric] cannot register " in it }?.let { throw GradleException("$console: $it") }
  val registered = lines.filter { "[Zabric] " in it && " as a ZombieBuddy preload mod " in it }
  if (registered.isNotEmpty()) {
    throw GradleException(
      "Preload mods were registered this launch; run $rerun again:\n" +
        registered.joinToString("\n") { "  ${it.substringAfter("[Zabric] ")}" },
    )
  }
  val widenerPrefix = "[Zabric] access widener: "
  val widener = lines.lastOrNull { widenerPrefix in it }?.substringAfter(widenerPrefix)
  if (widener?.startsWith("ok (") != true) {
    throw GradleException("$console: Zabric's widening is not ok: ${widener ?: "no '$widenerPrefix' line"}")
  }
  // every entrypoint error line of Zabric names them; the lines of entrypoints called do not
  lines.firstOrNull { "[Zabric] " in it && "entrypoint" in it }?.let { throw GradleException("$console: $it") }
  lines.firstOrNull { "[ZabricKotlin] not ready" in it }?.let { throw GradleException("$console: $it") }
}

/** Fails unless each of [lines] is in [log]; [where] names the log. */
fun requireLines(log: String, where: String, lines: List<String>) {
  val missing = lines.filterNot { it in log }
  if (missing.isNotEmpty()) {
    throw GradleException("$where is missing:\n" + missing.joinToString("\n") { "  $it" })
  }
}

/** Fails unless [lines] appear in [log] in this order. */
fun requireOrder(log: String, where: String, lines: List<String>) {
  val positions = lines.map { log.indexOf(it) }
  if (positions != positions.sorted()) {
    throw GradleException("$where has these lines out of order:\n" + lines.joinToString("\n") { "  $it" })
  }
}
