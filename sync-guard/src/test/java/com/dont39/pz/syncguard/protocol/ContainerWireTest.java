package com.dont39.pz.syncguard.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fixtures as ContainerID.write lays them out in the game build the mod was written against. */
class ContainerWireTest {
    private static final byte DEAD_BODY = 1;
    private static final byte WORLD_OBJECT = 2;
    private static final byte ISO_OBJECT = 3;
    private static final byte OBJECT_CONTAINER = 4;
    private static final byte OBJECT_IN_VEHICLE = 5;
    private static final byte VEHICLE = 6;
    private static final byte PLAYER_INVENTORY = 7;
    private static final byte INVENTORY_CONTAINER = 8;
    private static final byte FLOOR = 9;

    private static ByteBuffer out() {
        return ByteBuffer.allocate(256);
    }

    private static byte[] bytes(ByteBuffer b) {
        return Arrays.copyOf(b.array(), b.position());
    }

    // type, x, y, z
    private static ByteBuffer square(byte type, int x, int y, int z) {
        return out().put(type).putInt(x).putInt(y).put((byte) z);
    }

    @Test
    void typesAreInTheGamesOrder() {
        assertEquals("DeadBody", ContainerWire.TYPES.get(DEAD_BODY));
        assertEquals("WorldObject", ContainerWire.TYPES.get(WORLD_OBJECT));
        assertEquals("IsoObject", ContainerWire.TYPES.get(ISO_OBJECT));
        assertEquals("ObjectContainer", ContainerWire.TYPES.get(OBJECT_CONTAINER));
        assertEquals("ObjectInVehicle", ContainerWire.TYPES.get(OBJECT_IN_VEHICLE));
        assertEquals("Vehicle", ContainerWire.TYPES.get(VEHICLE));
        assertEquals("PlayerInventory", ContainerWire.TYPES.get(PLAYER_INVENTORY));
        assertEquals("InventoryContainer", ContainerWire.TYPES.get(INVENTORY_CONTAINER));
        assertEquals("Floor", ContainerWire.TYPES.get(FLOOR));
    }

    @Test
    void playerInventory() {
        // online id, player index
        byte[] wire = bytes(out().put(PLAYER_INVENTORY).putShort((short) 12).put((byte) 1));
        assertEquals(ContainerKey.player(12), ContainerWire.decode(wire));
    }

    @Test
    void bagInAPlayerInventory() {
        byte[] wire = bytes(out().put(INVENTORY_CONTAINER).putShort((short) 12).put((byte) 0).putInt(987654));
        assertEquals(ContainerKey.bag(12, 987654), ContainerWire.decode(wire));
    }

    @Test
    void ownerInventoryOfABag() {
        byte[] bag = bytes(out().put(INVENTORY_CONTAINER).putShort((short) 12).put((byte) 1).putInt(987654));
        byte[] inventory = bytes(out().put(PLAYER_INVENTORY).putShort((short) 12).put((byte) 1));
        assertArrayEquals(inventory, ContainerWire.ownerInventory(bag));
        byte[] notABag = bytes(out().put(PLAYER_INVENTORY).putShort((short) 12).put((byte) 1));
        assertThrows(ProtocolException.class, () -> ContainerWire.ownerInventory(notABag));
        assertThrows(ProtocolException.class, () -> ContainerWire.ownerInventory(new byte[] {INVENTORY_CONTAINER, 0}));
    }

    @Test
    void corpse() {
        byte[] wire = bytes(square(DEAD_BODY, 10500, 9600, 1).putShort((short) 3));
        assertEquals(ContainerKey.corpse(10500, 9600, 1, 3), ContainerWire.decode(wire));
    }

    @Test
    void bagOnTheFloor() {
        byte[] wire = bytes(square(WORLD_OBJECT, 10500, 9600, 0).putInt(-42));
        assertEquals(ContainerKey.worldItem(10500, 9600, 0, -42), ContainerWire.decode(wire));
    }

    @Test
    void objectsMainContainer() {
        byte[] wire = bytes(square(ISO_OBJECT, 10500, 9600, -1).putShort((short) 4));
        assertEquals(ContainerKey.object(10500, 9600, -1, 4), ContainerWire.decode(wire));
    }

    @Test
    void oneOfAnObjectsContainers() {
        byte[] wire = bytes(square(OBJECT_CONTAINER, 10500, 9600, 2).putShort((short) 4).putShort((short) 1));
        assertEquals(ContainerKey.objectContainer(10500, 9600, 2, 4, 1), ContainerWire.decode(wire));
    }

    @Test
    void vehiclePartIgnoresTheSquareItWasOn() {
        byte[] here = bytes(square(VEHICLE, 10500, 9600, 0).putShort((short) 77).putShort((short) 5));
        byte[] there = bytes(square(VEHICLE, 11000, 9000, 0).putShort((short) 77).putShort((short) 5));
        assertEquals(ContainerKey.vehicle(77, 5), ContainerWire.decode(here));
        assertEquals(ContainerWire.decode(here), ContainerWire.decode(there));
    }

    @Test
    void bagInAVehiclePart() {
        byte[] wire = bytes(square(OBJECT_IN_VEHICLE, 10500, 9600, 0).putShort((short) 77).putShort((short) 5)
                .putInt(123));
        assertEquals(ContainerKey.vehicleBag(77, 5, 123), ContainerWire.decode(wire));
    }

    @Test
    void shortsAreSignedAsTheGamesFields() {
        byte[] wire = bytes(square(VEHICLE, 0, 0, 0).putShort((short) -2).putShort((short) 0x8000));
        assertEquals(ContainerKey.vehicle(-2, Short.MIN_VALUE), ContainerWire.decode(wire));
    }

    @Test
    void floorHasNoKey() {
        assertNull(ContainerWire.decode(bytes(square(FLOOR, 10500, 9600, 0).put((byte) 0))));
        assertNull(ContainerWire.decode(bytes(square(FLOOR, 10500, 9600, 0).put((byte) 2)
                .putInt(10500).putInt(9600).putInt(10501).putInt(9600))));
    }

    @Test
    void floorWithMissingSquaresIsRefused() {
        byte[] wire = bytes(square(FLOOR, 10500, 9600, 0).put((byte) 2).putInt(10500).putInt(9600));
        assertThrows(ProtocolException.class, () -> ContainerWire.decode(wire));
    }

    @Test
    void undefinedIsRefused() {
        assertThrows(ProtocolException.class, () -> ContainerWire.decode(new byte[] {0}));
    }

    @Test
    void unknownTypeIsRefused() {
        assertThrows(ProtocolException.class, () -> ContainerWire.decode(bytes(square((byte) 10, 0, 0, 0))));
    }

    @Test
    void truncatedIsRefused() {
        byte[] whole = bytes(square(OBJECT_CONTAINER, 1, 2, 0).putShort((short) 4).putShort((short) 1));
        for (int length = 0; length < whole.length; length++) {
            byte[] part = Arrays.copyOf(whole, length);
            assertThrows(ProtocolException.class, () -> ContainerWire.decode(part), "length " + length);
        }
    }

    @Test
    void trailingBytesAreRefused() {
        byte[] wire = bytes(square(ISO_OBJECT, 1, 2, 0).putShort((short) 4).put((byte) 0));
        assertThrows(ProtocolException.class, () -> ContainerWire.decode(wire));
    }
}
