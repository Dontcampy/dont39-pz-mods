package com.dont39.pz.syncguard.protocol;

import java.util.List;

/**
 * A container by its parent, as the game's {@code ContainerID} addresses it, but with value semantics:
 * {@code ContainerID.equals} ignores the square of a player inventory while its {@code hashCode} hashes it. Unused
 * components are 0, null or empty. Build one with the factories.
 */
public record ContainerKey(Kind kind, int x, int y, int z, int owner, int index, int sub, ContainerKey anchor,
        List<Integer> bags) implements Key {
    /** The most bags a nested address names, the one in its anchor included. */
    public static final int MAX_BAGS = 8;

    public enum Kind {
        /** {@code owner}: the player's online id. */
        PLAYER(false, false),
        /** A bag at any depth in a player's inventory; {@code owner}: online id, {@code sub}: the bag's item id. */
        BAG(false, false),
        /** An object's main container; {@code index}: the object's index on its square. */
        OBJECT(true, true),
        /** One of an object's containers; {@code index}: the object's index, {@code sub}: the container's. */
        OBJECT_CONTAINER(true, true),
        /** {@code owner}: the vehicle id, {@code index}: the part's. */
        VEHICLE(false, true),
        /** A bag in a vehicle part; {@code owner}: vehicle id, {@code index}: part, {@code sub}: the bag's item id. */
        VEHICLE_BAG(false, false),
        /** {@code index}: the body's index among its square's static moving objects. */
        CORPSE(true, true),
        /** A bag lying on the floor; {@code sub}: the bag's item id. */
        WORLD_ITEM(true, true),
        /**
         * A bag the game's own ids cannot address (the mod's own kind): in a world, corpse or vehicle-part container,
         * or in a bag lying on the floor, at any depth. {@code anchor}: that outer container; {@code bags}: the bag
         * item ids from the one in it down to this one.
         */
        NESTED(false, false);

        private final boolean square;
        private final boolean anchors;

        Kind(boolean square, boolean anchors) {
            this.square = square;
            this.anchors = anchors;
        }

        /** Whether {@code x}, {@code y}, {@code z} are the container's square. */
        public boolean square() {
            return square;
        }

        /** Whether a nested address may start at a container of this kind. */
        public boolean anchors() {
            return anchors;
        }
    }

    public ContainerKey {
        bags = List.copyOf(bags);
        boolean nested = kind == Kind.NESTED;
        if (nested != (anchor != null) || nested == bags.isEmpty()) {
            throw new IllegalArgumentException("a " + kind + " key with anchor " + anchor + " and bags " + bags);
        }
    }

    private static ContainerKey of(Kind kind, int x, int y, int z, int owner, int index, int sub) {
        return new ContainerKey(kind, x, y, z, owner, index, sub, null, List.of());
    }

    public static ContainerKey player(int onlineId) {
        return of(Kind.PLAYER, 0, 0, 0, onlineId, 0, 0);
    }

    public static ContainerKey bag(int onlineId, int bagId) {
        return of(Kind.BAG, 0, 0, 0, onlineId, 0, bagId);
    }

    public static ContainerKey object(int x, int y, int z, int index) {
        return of(Kind.OBJECT, x, y, z, 0, index, 0);
    }

    public static ContainerKey objectContainer(int x, int y, int z, int index, int containerIndex) {
        return of(Kind.OBJECT_CONTAINER, x, y, z, 0, index, containerIndex);
    }

    public static ContainerKey vehicle(int vehicleId, int part) {
        return of(Kind.VEHICLE, 0, 0, 0, vehicleId, part, 0);
    }

    public static ContainerKey vehicleBag(int vehicleId, int part, int bagId) {
        return of(Kind.VEHICLE_BAG, 0, 0, 0, vehicleId, part, bagId);
    }

    public static ContainerKey corpse(int x, int y, int z, int index) {
        return of(Kind.CORPSE, x, y, z, 0, index, 0);
    }

    public static ContainerKey worldItem(int x, int y, int z, int bagId) {
        return of(Kind.WORLD_ITEM, x, y, z, 0, 0, bagId);
    }

    /** {@code bags}: from the one in {@code anchor} down to the addressed one, 1 to {@link #MAX_BAGS}. */
    public static ContainerKey nested(ContainerKey anchor, List<Integer> bags) {
        if (!anchor.kind().anchors()) {
            throw new IllegalArgumentException("a nested address cannot start at " + anchor);
        }
        if (bags.isEmpty() || bags.size() > MAX_BAGS) {
            throw new IllegalArgumentException("a nested address of " + bags.size() + " bags");
        }
        return new ContainerKey(Kind.NESTED, 0, 0, 0, 0, 0, 0, anchor, bags);
    }

    @Override
    public ContainerKey container() {
        return this;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case PLAYER -> "inventory of player " + owner;
            case BAG -> "bag " + sub + " of player " + owner;
            case OBJECT -> "object " + index + " at " + x + "," + y + "," + z;
            case OBJECT_CONTAINER -> "container " + sub + " of object " + index + " at " + x + "," + y + "," + z;
            case VEHICLE -> "part " + index + " of vehicle " + owner;
            case VEHICLE_BAG -> "bag " + sub + " in part " + index + " of vehicle " + owner;
            case CORPSE -> "corpse " + index + " at " + x + "," + y + "," + z;
            case WORLD_ITEM -> "bag " + sub + " on the floor at " + x + "," + y + "," + z;
            case NESTED -> {
                StringBuilder b = new StringBuilder();
                for (int i = bags.size() - 1; i >= 0; i--) {
                    b.append("bag ").append(bags.get(i)).append(" in ");
                }
                yield b.append(anchor).toString();
            }
        };
    }
}
