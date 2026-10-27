package com.dont39.pz.appearanceoverride

import zombie.core.ImmutableColor
import zombie.core.skinnedmodel.visual.ItemVisual
import zombie.scripting.ScriptManager

/** What drawing an [Override.Look] needs from the game's scripts, and the visual drawn in the worn item's place. */
internal object Looks {
  /** Null when [look] can be drawn as given, else why not. */
  fun problem(look: Override.Look): String? {
    val script = ScriptManager.instance.getItem(look.type) ?: return "there is no item type ${look.type}"
    val clothing = script.clothingItemAsset ?: return "${look.type} has no clothing model, so it cannot be worn"
    // out of range, the game's getters pick one at random on every model rebuild
    val choices = clothing.textureChoices.size
    if (choices > 0 && look.textureChoice !in 0 until choices) {
      return "textureChoice ${look.textureChoice} is not one of ${look.type}'s texture choices (0 to ${choices - 1})"
    }
    val bases = clothing.baseTextures.size
    if (bases > 0 && look.baseTexture !in 0 until bases) {
      return "baseTexture ${look.baseTexture} is not one of ${look.type}'s base textures (0 to ${bases - 1})"
    }
    if (!clothing.decalGroup.isNullOrBlank() && look.decal == null) {
      return "${look.type} draws a decal from group ${clothing.decalGroup}, and none is given"
    }
    return null
  }

  /** A visual of [look]'s type with every value set; it has no inventory item, so the item's own colour is not used. */
  fun substitute(look: Override.Look): ItemVisual = ItemVisual().apply {
    setItemType(look.type)
    setTextureChoice(look.textureChoice)
    setBaseTexture(look.baseTexture)
    setTint(ImmutableColor(look.tint.r, look.tint.g, look.tint.b, look.tint.a))
    setDecal(look.decal)
    setHue(look.hue)
  }

  /** [substitute] with [worn]'s blood, dirt, holes and patches, as the worn item would show them. */
  fun dress(substitute: ItemVisual, worn: ItemVisual): ItemVisual = substitute.apply {
    copyBlood(worn)
    copyDirt(worn)
    copyHoles(worn)
    copyPatches(worn)
  }
}

/** What a render consumer gets in a worn item's place. */
internal sealed interface Drawn {
  data object Hidden : Drawn

  /** Shared by every hand-out until the override changes, so the model and texture passes draw the same visual. */
  class As(val visual: ItemVisual) : Drawn

  companion object {
    fun of(override: Override): Drawn = when (override) {
      Override.Hidden -> Hidden
      is Override.Look -> As(Looks.substitute(override))
    }
  }
}
