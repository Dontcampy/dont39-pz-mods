package com.dont39.pz.modsync.client

import com.dont39.pz.common.describe
import com.dont39.pz.modsync.client.download.Cache
import com.dont39.pz.modsync.client.download.Fetcher
import com.dont39.pz.modsync.client.download.LocalCopy
import com.dont39.pz.modsync.client.download.Plan
import com.dont39.pz.modsync.client.download.Progress
import com.dont39.pz.modsync.client.java.JavaMod
import com.dont39.pz.modsync.client.java.Launch
import com.dont39.pz.modsync.client.java.ModInfo
import com.dont39.pz.modsync.client.swap.SwapEntry
import com.dont39.pz.modsync.protocol.SafePath
import com.dont39.pz.modsync.protocol.SyncException
import java.io.IOException
import java.net.URI
import java.nio.file.Path

/**
 * One join to a server that offers its Workshop items, from its connection-details to the end of the `CheckMods`
 * hold. Polled on the game thread, which alone touches it; its workers only report through their outcome. Game-free:
 * the install folder lookup, this launch's Java side and the log are passed in.
 *
 * An item whose server copy changes a jar is never loaded at the join: Java classes cannot be unloaded, so the next
 * launch takes the item from its tree through Zabric's override, and the join stops for that restart.
 */
internal class JoinSession(
  val host: String,
  val gamePort: Int,
  val base: URI,
  private val cache: Cache,
  private val installFolder: (Long) -> String?,
  private val launch: Launch,
  private val log: (() -> String) -> Unit,
) {
  /** An item the Workshop step handed over: [timestamp] is the server's version, [title] Steam's name for it. */
  class Marked(val id: Long, val timestamp: Long, val title: String?) {
    val label: String
      get() = title?.let { "\"$it\" ($id)" } ?: "Workshop item $id"
  }

  /** One item's tree; [jarChanges] as [Plan.jarChanges]. */
  sealed class Work(val marked: Marked, val tree: Path, val install: Path, val jarChanges: List<String>) {
    /** The folder the swap and the override use. */
    val treeMods: Path = tree.resolve(SafePath.MODS).toAbsolutePath().normalize()

    /** Built by an earlier join. */
    class Reused(marked: Marked, tree: Path, install: Path, jarChanges: List<String>) :
      Work(marked, tree, install, jarChanges)

    /** Built by this join, as [plan] says, copying from [local]. */
    class Build(marked: Marked, tree: Path, install: Path, val local: LocalCopy, val plan: Plan) :
      Work(marked, tree, install, plan.jarChanges)
  }

  class Prepared(val fetcher: Fetcher, val items: List<Work>, val remembered: Boolean) {
    val builds: List<Work.Build> = items.filterIsInstance<Work.Build>()
    val java: List<Work> = items.filter { it.jarChanges.isNotEmpty() }

    /** What the dialog lists: the items that download anything or change a jar. */
    val listed: List<Work> = items.filter { w -> w in java || (w is Work.Build && w.plan.download.isNotEmpty()) }
    val downloadFiles: Int = builds.sumOf { it.plan.download.size }
    val downloadBytes: Long = builds.sumOf { it.plan.downloadBytes }
    val copyFiles: Int = builds.sumOf { it.plan.copy.size }
  }

  /** Thrown on a worker: the join stops as [stop] says. */
  class StopJoin(val stop: Step.Stop) : Exception(stop.why)

  enum class Answer { ACCEPT, REMEMBER, DECLINE, DIALOG_FAILED }

  sealed interface Step {
    data object Hold : Step

    /** Swap these on and let the join go on; [expected] are to run from their trees once the join's mods load. */
    class Ready(val entries: List<SwapEntry>, val expected: List<JavaMod> = emptyList()) : Step

    /**
     * Stop the join with the text of [key] after [KEY_PREFIX], [args] its arguments; [why] for the log. [restart]: the
     * next launch is set up for this server, so the player is asked to restart.
     */
    class Stop(val key: String, val args: List<String>, val why: String, val restart: Boolean = false) : Step

    /** An error of the feature: disable it and stop the join. */
    class Broken(val what: String, val error: Throwable) : Step
  }

  sealed interface Phase {
    data object Start : Phase

    class Preparing(val worker: Worker<Prepared>) : Phase

    class Confirm(val prepared: Prepared) : Phase

    class Building(val worker: Worker<Unit>, val prepared: Prepared) : Phase

    data object Over : Phase
  }

  private val marked = linkedMapOf<Long, Marked>()

  var phase: Phase = Phase.Start
    private set

  private var answer: Answer? = null

  val hasMarks: Boolean
    get() = marked.isNotEmpty()

  fun mark(item: Marked) {
    check(phase == Phase.Start) { "a Workshop item failed after the join checked its mods" }
    marked[item.id] = item
  }

  /** From the dialog; only while it asks. */
  fun answer(answer: Answer) {
    check(phase is Phase.Confirm) { "an answer came while no question was asked" }
    this.answer = answer
  }

  fun poll(): Step = when (val p = phase) {
    Phase.Start -> {
      val items = marked.values.toList()
      // Steam's API on the game thread; the rest on the worker
      val installs = items.associate { m ->
        m.id to Path.of(checkNotNull(installFolder(m.id)) { "Steam has no install folder for ${m.label}" })
      }
      phase = Phase.Preparing(Worker("Dont39AllInOne mod-sync prepare") { prepare(items, installs, it) }.start())
      Step.Hold
    }
    is Phase.Preparing -> p.worker.outcome?.let { outcome(it, "preparing the server's Workshop items", ::prepared) }
      ?: Step.Hold
    is Phase.Confirm -> confirmed(p.prepared)
    is Phase.Building -> p.worker.outcome?.let { o -> outcome(o, "downloading the server's Workshop items") { built(p) } }
      ?: Step.Hold
    Phase.Over -> throw IllegalStateException("the join was polled after its hold ended")
  }

  fun cancel() {
    when (val p = phase) {
      is Phase.Preparing -> p.worker.cancel()
      is Phase.Building -> p.worker.cancel()
      else -> {}
    }
    phase = Phase.Over
  }

  private fun prepare(items: List<Marked>, installs: Map<Long, Path>, progress: Progress): Prepared {
    val fetcher = Fetcher(base)
    val manifest = fetcher.manifest()
    val work = items.map { m ->
      progress.checkCancelled()
      val item = manifest.item(m.id, m.timestamp)
      val install = installs.getValue(m.id)
      Refusal.of(m.id, Refusal.folders(item) + Refusal.folders(install))?.let { carried ->
        throw StopJoin(Step.Stop("Failed_Refused", listOf(m.label, carried), "${m.label} carries $carried"))
      }
      val tree = cache.tree(host, gamePort, item)
      val ready = cache.ready(tree, item)
      // a ready tree still has its jars compared: the client's own copy may have changed since
      val compared = if (ready) item.copy(files = item.files.filter { Plan.isJar(it.path) }) else item
      val local = LocalCopy.scan(install, compared, progress)
      val plan = Plan.of(compared, local)
      if (ready) Work.Reused(m, tree, install, plan.jarChanges) else Work.Build(m, tree, install, local, plan)
    }
    return Prepared(fetcher, work, cache.servers.remembered(host, gamePort))
  }

  private fun prepared(prepared: Prepared): Step {
    prepared.items.forEach { w ->
      log {
        when (w) {
          is Work.Reused ->
            "${w.marked.label}: the server's version ${w.marked.timestamp} is ready from an earlier join"
          is Work.Build ->
            "${w.marked.label}: the server's version ${w.marked.timestamp} takes ${w.plan.copy.size} files from " +
              "your copy and ${w.plan.download.size} files (${w.plan.downloadBytes} bytes) from the server"
        }
      }
      if (w.jarChanges.isNotEmpty()) {
        log { "${w.marked.label}: the server's version has other jars: ${w.jarChanges.joinToString(", ")}" }
      }
    }
    return decide {
      // before any download: the client's copy already tells
      preloadStop(prepared.java) { it.install.resolve(SafePath.MODS) }?.let { return@decide stop(it) }
      // Java code from a server always asks; the Rejoin after the restart was answered before it
      val overridden = overridden(prepared.java)
      val askJava = prepared.java.any { it.marked.id !in overridden }
      when {
        askJava || (prepared.downloadFiles > 0 && !prepared.remembered) -> {
          log {
            "asking whether to download ${prepared.downloadBytes} bytes from $host:$gamePort" +
              if (askJava) " and run Java code from it" else ""
          }
          phase = Phase.Confirm(prepared)
          Step.Hold
        }
        prepared.builds.isEmpty() -> ready(prepared)
        else -> build(prepared, remember = false)
      }
    }
  }

  private fun confirmed(prepared: Prepared): Step {
    val a = answer ?: return Step.Hold
    log { "answer: ${a.name.lowercase()}" }
    return when (a) {
      Answer.ACCEPT -> build(prepared, remember = false)
      Answer.REMEMBER -> build(prepared, remember = true)
      Answer.DECLINE -> stop(Step.Stop("Failed_Declined", emptyList(), "the player declined the download"))
      Answer.DIALOG_FAILED -> stop(Step.Stop("Failed_Dialog", emptyList(), "the download dialog failed"))
    }
  }

  private fun build(prepared: Prepared, remember: Boolean): Step {
    log {
      "building ${prepared.builds.size} trees: ${prepared.downloadFiles} files (${prepared.downloadBytes} bytes) " +
        "from $base, ${prepared.copyFiles} files from your copy"
    }
    val worker = Worker<Unit>("Dont39AllInOne mod-sync download") { progress ->
      progress.begin(prepared.downloadFiles, prepared.downloadBytes)
      if (remember) {
        cache.servers.remember(host, gamePort)
      }
      prepared.builds.forEach { w -> cache.build(w.tree, w.plan, w.local, prepared.fetcher, progress) }
    }
    phase = Phase.Building(worker.start(), prepared)
    return Step.Hold
  }

  private fun built(building: Phase.Building): Step {
    val progress = building.worker.progress
    log { "downloaded ${progress.filesDone} files (${progress.bytesDone} bytes)" }
    return ready(building.prepared)
  }

  /** Once every tree is built: the Java side of the items that change a jar decides how the join goes on. */
  private fun ready(prepared: Prepared): Step = decide {
    val overridden = overridden(prepared.java)
    val mainMenu by lazy { launch.mainMenuMods() }
    val expected = prepared.java.filter { it.marked.id in overridden }.flatMap { w ->
      javaMods(w.treeMods).onEach { mod -> notServed(w, mod, mainMenu)?.let { return@decide stop(it) } }
    }
    val notYet = prepared.java.filter { it.marked.id !in overridden }
    if (notYet.isNotEmpty()) {
      return@decide restart(prepared.java, notYet)
    }
    phase = Phase.Over
    Step.Ready(prepared.items.map { w -> SwapEntry.of(w.marked.id, w.install, w.tree) }, expected)
  }

  /** The items of [java] whose tree this launch already uses, through Zabric's override. */
  private fun overridden(java: List<Work>): Set<Long> {
    if (java.isEmpty()) {
      return emptySet()
    }
    val current = launch.current()
    return java.filter { w -> current[w.marked.id.toString()]?.toAbsolutePath()?.normalize() == w.treeMods }
      .mapTo(hashSetOf()) { it.marked.id }
  }

  /** Why [mod] of an overridden item does not run from its tree at this join; null when it does or will. */
  private fun notServed(w: Work, mod: JavaMod, mainMenu: Set<String>): Step.Stop? {
    val served = launch.servingJar(mod.pkg)
    val tree = mod.jar.toRealPath()
    return when (served) {
      tree -> null
      // the join's pass loads it from the tree, unless the loader skipped it in the main menu's pass that listed it
      null -> if (mod.id != null && mod.id in mainMenu) {
        Step.Stop(
          "Failed_NotLoaded",
          listOf(mod.name, w.marked.label),
          "${w.marked.label}: the game did not load ${mod.name} from $tree at its start",
        )
      } else {
        null
      }
      else -> Step.Stop(
        "Failed_NotServed",
        listOf(mod.name, served.toString()),
        "${w.marked.label}: the game runs ${mod.pkg} of ${mod.name} from $served, not from $tree",
      )
    }
  }

  /**
   * Sets up the next launch to take every item of [java] from its tree, keeping what another server requested, and
   * stops the join; [notYet] are those this launch does not take from there yet.
   */
  private fun restart(java: List<Work>, notYet: List<Work>): Step {
    // the server's mod.info may differ from the client's, which was checked before the download
    preloadStop(java) { it.treeMods }?.let { return stop(it) }
    val request = launch.pending() + java.associate { it.marked.id.toString() to it.treeMods }
    launch.request(request)
    log { "requested for the next launch: ${request.entries.joinToString(", ") { (id, mods) -> "$id from $mods" }}" }
    val labels = notYet.joinToString(", ") { it.marked.label }
    return stop(Step.Stop("Failed_Restart", listOf(labels), "$labels need a restart for their Java code", true))
  }

  private fun preloadStop(items: List<Work>, modsFolder: (Work) -> Path): Step.Stop? {
    items.forEach { w ->
      javaMods(modsFolder(w)).firstOrNull { it.preload }?.let { mod ->
        return Step.Stop(
          "Failed_Preload",
          listOf(mod.name, w.marked.label),
          "${w.marked.label}: ${mod.name} loads at game start (${mod.jar})",
        )
      }
    }
    return null
  }

  private fun javaMods(modsFolder: Path): List<JavaMod> = ModInfo.javaMods(modsFolder, launch::versionFolder)

  /** A file that cannot be read stops the join; anything else is an error of the feature. */
  private inline fun decide(body: () -> Step): Step = try {
    body()
  } catch (e: IOException) {
    describe(e).let { stop(Step.Stop("Failed", listOf(it), "checking the server's Java mods: $it")) }
  } catch (t: Throwable) {
    phase = Phase.Over
    Step.Broken("checking the server's Java mods", t)
  }

  private fun <T> outcome(outcome: Worker.Outcome<T>, what: String, done: (T) -> Step): Step = when (outcome) {
    is Worker.Outcome.Done -> done(outcome.value)
    is Worker.Outcome.Failed -> failed(outcome.error, what)
    Worker.Outcome.Cancelled -> Step.Broken(what, IllegalStateException("the worker was cancelled while polled"))
  }

  private fun failed(e: Throwable, what: String): Step = when (e) {
    is StopJoin -> stop(e.stop)
    is SyncException -> stop(Step.Stop("Failed", listOf(checkNotNull(e.message)), checkNotNull(e.message)))
    // a runtime event on the network or disk, not an error of the feature
    is IOException -> describe(e).let { stop(Step.Stop("Failed_Network", listOf(base.authority, it), "$what: $it")) }
    else -> {
      phase = Phase.Over
      Step.Broken(what, e)
    }
  }

  private fun stop(stop: Step.Stop): Step {
    phase = Phase.Over
    return stop
  }

  companion object {
    const val KEY_PREFIX = "UI_Dont39AllInOne_ModSync_"
  }
}
