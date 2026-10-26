package com.dont39.pz.serverreborn.network.protocol

/** What the server tracks per connection: a container or an item in one. */
internal sealed interface Key {
  val container: ContainerKey
}

/**
 * An item by its id, with the container it was seen in: the server resolves an item only through its container, and a
 * moved item shows up as gone here and changed in its new container.
 */
internal data class ItemKey(override val container: ContainerKey, val itemId: Int) : Key {
  override fun toString() = "item $itemId in $container"
}

/**
 * A container by its parent, as the game's `ContainerID` addresses it, but with value semantics: `ContainerID.equals`
 * ignores the square of a player inventory while its `hashCode` hashes it. Unused components are 0, null or empty.
 * Build one with the factories.
 */
@ConsistentCopyVisibility
internal data class ContainerKey private constructor(
  val kind: Kind,
  val x: Int,
  val y: Int,
  val z: Int,
  val owner: Int,
  val index: Int,
  val sub: Int,
  val anchor: ContainerKey?,
  val bags: List<Int>,
) : Key {
  /**
   * [square]: whether [x], [y], [z] are the container's square. [anchors]: whether a nested address may start at a
   * container of this kind.
   */
  enum class Kind(val square: Boolean, val anchors: Boolean) {
    /** [owner]: the player's online id. */
    PLAYER(false, false),

    /** A bag at any depth in a player's inventory; [owner]: online id, [sub]: the bag's item id. */
    BAG(false, false),

    /** An object's main container; [index]: the object's index on its square. */
    OBJECT(true, true),

    /** One of an object's containers; [index]: the object's index, [sub]: the container's. */
    OBJECT_CONTAINER(true, true),

    /** [owner]: the vehicle id, [index]: the part's. */
    VEHICLE(false, true),

    /** A bag in a vehicle part; [owner]: vehicle id, [index]: part, [sub]: the bag's item id. */
    VEHICLE_BAG(false, false),

    /** [index]: the body's index among its square's static moving objects. */
    CORPSE(true, true),

    /** A bag lying on the floor; [sub]: the bag's item id. */
    WORLD_ITEM(true, true),

    /**
     * A bag the game's own ids cannot address (the mod's own kind): in a world, corpse or vehicle-part container, or
     * in a bag lying on the floor, at any depth. [anchor]: that outer container; [bags]: the bag item ids from the one
     * in it down to this one.
     */
    NESTED(false, false),
  }

  override val container: ContainerKey get() = this

  override fun toString() = when (kind) {
    Kind.PLAYER -> "inventory of player $owner"
    Kind.BAG -> "bag $sub of player $owner"
    Kind.OBJECT -> "object $index at $x,$y,$z"
    Kind.OBJECT_CONTAINER -> "container $sub of object $index at $x,$y,$z"
    Kind.VEHICLE -> "part $index of vehicle $owner"
    Kind.VEHICLE_BAG -> "bag $sub in part $index of vehicle $owner"
    Kind.CORPSE -> "corpse $index at $x,$y,$z"
    Kind.WORLD_ITEM -> "bag $sub on the floor at $x,$y,$z"
    Kind.NESTED -> bags.asReversed().joinToString("") { "bag $it in " } + anchor
  }

  companion object {
    /** The most bags a nested address names, the one in its anchor included. */
    const val MAX_BAGS = 8

    private fun of(kind: Kind, x: Int = 0, y: Int = 0, z: Int = 0, owner: Int = 0, index: Int = 0, sub: Int = 0) =
      ContainerKey(kind, x, y, z, owner, index, sub, null, emptyList())

    fun player(onlineId: Int) = of(Kind.PLAYER, owner = onlineId)

    fun bag(onlineId: Int, bagId: Int) = of(Kind.BAG, owner = onlineId, sub = bagId)

    fun isoObject(x: Int, y: Int, z: Int, index: Int) = of(Kind.OBJECT, x, y, z, index = index)

    fun objectContainer(x: Int, y: Int, z: Int, index: Int, containerIndex: Int) =
      of(Kind.OBJECT_CONTAINER, x, y, z, index = index, sub = containerIndex)

    fun vehicle(vehicleId: Int, part: Int) = of(Kind.VEHICLE, owner = vehicleId, index = part)

    fun vehicleBag(vehicleId: Int, part: Int, bagId: Int) =
      of(Kind.VEHICLE_BAG, owner = vehicleId, index = part, sub = bagId)

    fun corpse(x: Int, y: Int, z: Int, index: Int) = of(Kind.CORPSE, x, y, z, index = index)

    fun worldItem(x: Int, y: Int, z: Int, bagId: Int) = of(Kind.WORLD_ITEM, x, y, z, sub = bagId)

    /** [bags]: from the one in [anchor] down to the addressed one, 1 to [MAX_BAGS]. */
    fun nested(anchor: ContainerKey, bags: List<Int>): ContainerKey {
      require(anchor.kind.anchors) { "a nested address cannot start at $anchor" }
      require(bags.size in 1..MAX_BAGS) { "a nested address of ${bags.size} bags" }
      return ContainerKey(Kind.NESTED, 0, 0, 0, 0, 0, 0, anchor, bags.toList())
    }
  }
}
