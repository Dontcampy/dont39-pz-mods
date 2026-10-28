package com.dont39.pz.appearanceoverride

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import zombie.characters.IsoGameCharacter
import zombie.characters.IsoPlayer
import zombie.characters.SurvivorDesc
import zombie.characters.animals.IsoAnimal
import zombie.core.skinnedmodel.DeadBodyAtlas
import zombie.core.skinnedmodel.ModelManager
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel
import zombie.core.skinnedmodel.model.ModelInstanceTextureCreator
import zombie.core.skinnedmodel.population.PopTemplateManager
import zombie.iso.objects.IsoMannequin

/**
 * The render consumers of worn items' visuals, open while one runs on this thread. Only there are the visual lists
 * edited: the same getters serve defense, coverage, sounds and the game's packets.
 */
internal object RenderScope {
  /** [player]: the player drawn, whose set applies; null when the items' own mod data does (corpses, mannequins). */
  class Frame(val consumer: String, val player: IsoPlayer?)

  private val frames = ThreadLocal.withInitial { ArrayList<Frame>() }

  fun enter(consumer: String, character: IsoGameCharacter?) {
    // an animal is an IsoPlayer without a set
    val player = (character as? IsoPlayer)?.takeUnless { it is IsoAnimal }
    frames.get() += Frame(consumer, player)
  }

  fun exit() {
    val open = frames.get()
    check(open.isNotEmpty()) { "a render consumer returned without an open scope" }
    open.removeAt(open.lastIndex)
  }

  /** The innermost open scope, null outside one. */
  fun current(): Frame? = frames.get().lastOrNull()
}

/** A character's world model: its clothing models and body masks. */
@Hooks(PopTemplateManager::class)
internal object ModelScope {
  @Inject(method = ["populateCharacterModelSlot"], at = [At("HEAD")])
  fun enter(character: IsoGameCharacter?, slot: ModelManager.ModelSlot?, ci: CallbackInfo) =
    RenderScope.enter("model", character)

  @Inject(method = ["populateCharacterModelSlot"], at = [At("FINALLY")])
  fun exit(ci: CallbackInfo) = RenderScope.exit()
}

/** Which attached items a worn item hides. */
@Hooks(ModelManager::class)
internal object AttachedScope {
  @Inject(method = ["DoCharacterModelEquipped"], at = [At("HEAD")])
  fun enter(character: IsoGameCharacter?, slot: ModelManager.ModelSlot?, ci: CallbackInfo) =
    RenderScope.enter("attached items", character)

  @Inject(method = ["DoCharacterModelEquipped"], at = [At("FINALLY")])
  fun exit(ci: CallbackInfo) = RenderScope.exit()
}

/** A character's clothing textures, matched to its models by clothing item. */
@Hooks(ModelInstanceTextureCreator::class)
internal object TextureScope {
  private const val INIT = "init(Lzombie/characters/IsoGameCharacter;)V"

  @Inject(method = [INIT], at = [At("HEAD")])
  fun enter(character: IsoGameCharacter?, ci: CallbackInfo) = RenderScope.enter("textures", character)

  @Inject(method = [INIT], at = [At("FINALLY")])
  fun exit(ci: CallbackInfo) = RenderScope.exit()
}

/** UI previews (character screen, other mods' panels): they copy the list once and build their model from the copy. */
@Hooks(AnimatedModel::class)
internal object PreviewScope {
  @Inject(method = ["setCharacter"], at = [At("HEAD")])
  fun character(character: IsoGameCharacter?, ci: CallbackInfo) = RenderScope.enter("preview", character)

  @Inject(method = ["setCharacter"], at = [At("FINALLY")])
  fun characterDone(ci: CallbackInfo) = RenderScope.exit()

  // a desc holds real items, drawn from their mod data
  @Inject(method = ["setSurvivorDesc"], at = [At("HEAD")])
  fun desc(desc: SurvivorDesc?, ci: CallbackInfo) = RenderScope.enter("preview", null)

  @Inject(method = ["setSurvivorDesc"], at = [At("FINALLY")])
  fun descDone(ci: CallbackInfo) = RenderScope.exit()
}

/** Corpse, zombie and mannequin images in the body atlas. */
@Hooks(DeadBodyAtlas.BodyParams::class)
internal object BodyAtlasScope {
  private const val DEAD_BODY = "init(Lzombie/iso/objects/IsoDeadBody;)V"
  private const val ZOMBIE = "init(Lzombie/characters/IsoZombie;)V"
  private const val MANNEQUIN = "init(Lzombie/iso/objects/IsoMannequin;)V"

  @Inject(method = [DEAD_BODY], at = [At("HEAD")])
  fun deadBody(ci: CallbackInfo) = RenderScope.enter("body atlas", null)

  @Inject(method = [DEAD_BODY], at = [At("FINALLY")])
  fun deadBodyDone(ci: CallbackInfo) = RenderScope.exit()

  @Inject(method = [ZOMBIE], at = [At("HEAD")])
  fun zombie(ci: CallbackInfo) = RenderScope.enter("body atlas", null)

  @Inject(method = [ZOMBIE], at = [At("FINALLY")])
  fun zombieDone(ci: CallbackInfo) = RenderScope.exit()

  @Inject(method = [MANNEQUIN], at = [At("HEAD")])
  fun mannequin(ci: CallbackInfo) = RenderScope.enter("body atlas", null)

  @Inject(method = [MANNEQUIN], at = [At("FINALLY")])
  fun mannequinDone(ci: CallbackInfo) = RenderScope.exit()
}

/** Corpses and mannequins drawn into a chunk's render target. */
@Hooks(targets = ["zombie.iso.fboRenderChunk.FBORenderCorpses\$BodyParams"])
internal object ChunkCorpseScope {
  private const val DEAD_BODY = "init(Lzombie/iso/objects/IsoDeadBody;)V"
  private const val MANNEQUIN = "init(Lzombie/iso/objects/IsoMannequin;)V"

  @Inject(method = [DEAD_BODY], at = [At("HEAD")])
  fun deadBody(ci: CallbackInfo) = RenderScope.enter("chunk corpses", null)

  @Inject(method = [DEAD_BODY], at = [At("FINALLY")])
  fun deadBodyDone(ci: CallbackInfo) = RenderScope.exit()

  @Inject(method = [MANNEQUIN], at = [At("HEAD")])
  fun mannequin(ci: CallbackInfo) = RenderScope.enter("chunk corpses", null)

  @Inject(method = [MANNEQUIN], at = [At("FINALLY")])
  fun mannequinDone(ci: CallbackInfo) = RenderScope.exit()
}

/** A mannequin's own model. */
@Hooks(IsoMannequin::class)
internal object MannequinScope {
  @Inject(method = ["syncModel"], at = [At("HEAD")])
  fun enter(ci: CallbackInfo) = RenderScope.enter("mannequin", null)

  @Inject(method = ["syncModel"], at = [At("FINALLY")])
  fun exit(ci: CallbackInfo) = RenderScope.exit()
}
