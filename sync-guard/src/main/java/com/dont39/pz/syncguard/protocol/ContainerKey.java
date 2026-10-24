package com.dont39.pz.syncguard.protocol;

/**
 * A container by its parent, as the game's {@code ContainerID} addresses it, but with value semantics:
 * {@code ContainerID.equals} ignores the square of a player inventory while its {@code hashCode} hashes it. Unused
 * components are 0. Build one with the factories.
 */
public record ContainerKey(Kind kind, int x, int y, int z, int owner, int index, int sub) implements Key {
    public enum Kind {
        /** {@code owner}: the player's online id. */
        PLAYER(false),
        /** A bag at any depth in a player's inventory; {@code owner}: online id, {@code sub}: the bag's item id. */
        BAG(false),
        /** An object's main container; {@code index}: the object's index on its square. */
        OBJECT(true),
        /** One of an object's containers; {@code index}: the object's index, {@code sub}: the container's. */
        OBJECT_CONTAINER(true),
        /** {@code owner}: the vehicle id, {@code index}: the part's. */
        VEHICLE(false),
        /** A bag in a vehicle part; {@code owner}: vehicle id, {@code index}: part, {@code sub}: the bag's item id. */
        VEHICLE_BAG(false),
        /** {@code index}: the body's index among its square's static moving objects. */
        CORPSE(true),
        /** A bag lying on the floor; {@code sub}: the bag's item id. */
        WORLD_ITEM(true);

        private final boolean square;

        Kind(boolean square) {
            this.square = square;
        }

        /** Whether {@code x}, {@code y}, {@code z} are the container's square. */
        public boolean square() {
            return square;
        }
    }

    public static ContainerKey player(int onlineId) {
        return new ContainerKey(Kind.PLAYER, 0, 0, 0, onlineId, 0, 0);
    }

    public static ContainerKey bag(int onlineId, int bagId) {
        return new ContainerKey(Kind.BAG, 0, 0, 0, onlineId, 0, bagId);
    }

    public static ContainerKey object(int x, int y, int z, int index) {
        return new ContainerKey(Kind.OBJECT, x, y, z, 0, index, 0);
    }

    public static ContainerKey objectContainer(int x, int y, int z, int index, int containerIndex) {
        return new ContainerKey(Kind.OBJECT_CONTAINER, x, y, z, 0, index, containerIndex);
    }

    public static ContainerKey vehicle(int vehicleId, int part) {
        return new ContainerKey(Kind.VEHICLE, 0, 0, 0, vehicleId, part, 0);
    }

    public static ContainerKey vehicleBag(int vehicleId, int part, int bagId) {
        return new ContainerKey(Kind.VEHICLE_BAG, 0, 0, 0, vehicleId, part, bagId);
    }

    public static ContainerKey corpse(int x, int y, int z, int index) {
        return new ContainerKey(Kind.CORPSE, x, y, z, 0, index, 0);
    }

    public static ContainerKey worldItem(int x, int y, int z, int bagId) {
        return new ContainerKey(Kind.WORLD_ITEM, x, y, z, 0, 0, bagId);
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
        };
    }
}
