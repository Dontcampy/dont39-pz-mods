package com.dont39.pz.modsync.client

import com.dont39.pz.common.describe
import com.dont39.pz.modsync.client.download.Cache
import com.dont39.pz.modsync.client.download.Fetcher
import com.dont39.pz.modsync.client.download.LocalCopy
import com.dont39.pz.modsync.client.download.Plan
import com.dont39.pz.modsync.client.download.Progress
import com.dont39.pz.modsync.client.swap.SwapEntry
import com.dont39.pz.modsync.protocol.SyncException
import java.io.IOException
import java.net.URI
import java.nio.file.Path

/**
 * One join to a server that offers its Workshop items, from its connection-details to the end of the `CheckMods`
 * hold. Polled on the game thread, which alone touches it; its workers only report through their outcome. Game-free:
 * the install folder lookup and the log are passed in.
 */
internal class JoinSession(
  val host: String,
  val gamePort: Int,
  val base: URI,
  private val cache: Cache,
  private val installFolder: (Long) -> String?,
  private val log: (() -> String) -> Unit,
) {
  /** An item the Workshop step handed over: [timestamp] is the server's version, [title] Steam's name for it. */
  class Marked(val id: Long, val timestamp: Long, val title: String?) {
    val label: String
      get() = title?.let { "\"$it\" ($id)" } ?: "Workshop item $id"
  }

  /** One item's tree. */
  sealed class Work(val marked: Marked, val tree: Path, val install: Path) {
    /** Built by an earlier join. */
    class Reused(marked: Marked, tree: Path, install: Path) : Work(marked, tree, install)

    /** Built by this join, as [plan] says, copying from [local]. */
    class Build(marked: Marked, tree: Path, install: Path, val local: LocalCopy, val plan: Plan) :
      Work(marked, tree, install)
  }

  class Prepared(val fetcher: Fetcher, val items: List<Work>, val remembered: Boolean) {
    val builds: List<Work.Build> = items.filterIsInstance<Work.Build>()
    val downloads: List<Work.Build> = builds.filter { it.plan.download.isNotEmpty() }
    val downloadFiles: Int = builds.sumOf { it.plan.download.size }
    val downloadBytes: Long = builds.sumOf { it.plan.downloadBytes }
    val copyFiles: Int = builds.sumOf { it.plan.copy.size }
  }

  /** Thrown on a worker: the join stops as [stop] says. */
  class StopJoin(val stop: Step.Stop) : Exception(stop.why)

  enum class Answer { ACCEPT, REMEMBER, DECLINE, DIALOG_FAILED }

  sealed interface Step {
    data object Hold : Step

    /** Swap these on and let the join go on. */
    class Ready(val entries: List<SwapEntry>) : Step

    /** Stop the join with the text of [key] after [KEY_PREFIX], [args] its arguments; [why] for the log. */
    class Stop(val key: String, val args: List<String>, val why: String) : Step

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
      if (plan.jarChanges.isNotEmpty()) {
        val jars = plan.jarChanges.joinToString(", ")
        throw StopJoin(
          Step.Stop("Failed_Jar", listOf(m.label, jars), "the server's copy of ${m.label} has other jars: $jars"),
        )
      }
      if (ready) Work.Reused(m, tree, install) else Work.Build(m, tree, install, local, plan)
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
    }
    if (prepared.builds.isEmpty()) {
      return ready(prepared)
    }
    if (prepared.downloadFiles > 0 && !prepared.remembered) {
      log { "asking whether to download ${prepared.downloadBytes} bytes from $host:$gamePort" }
      phase = Phase.Confirm(prepared)
      return Step.Hold
    }
    return build(prepared, remember = false)
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

  private fun ready(prepared: Prepared): Step {
    phase = Phase.Over
    return Step.Ready(
      prepared.items.map { w ->
        SwapEntry.of(w.marked.id, w.install, w.tree)
      },
    )
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
