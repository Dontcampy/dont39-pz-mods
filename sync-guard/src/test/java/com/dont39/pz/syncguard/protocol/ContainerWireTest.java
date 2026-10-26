package com.dont39.pz.syncguard.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ---- nested addresses (the mod's own)

    private static final byte[] CRATE = bytes(square(OBJECT_CONTAINER, 10500, 9600, 0).putShort((short) 4)
            .putShort((short) 1));
    private static final ContainerKey CRATE_KEY = ContainerKey.objectContainer(10500, 9600, 0, 4, 1);

    @Test
    void nestedOneBagDeep() {
        byte[] wire = ContainerWire.nested(CRATE, List.of(-77));
        assertArrayEquals(bytes(out().put((byte) ContainerWire.NESTED).put((byte) 1).putInt(-77).put(CRATE)), wire);
        ContainerKey key = ContainerWire.decode(wire);
        assertEquals(ContainerKey.nested(CRATE_KEY, List.of(-77)), key);
        assertEquals(ContainerKey.Kind.NESTED, key.kind());
        assertEquals(CRATE_KEY, key.anchor());
        assertArrayEquals(CRATE, ContainerWire.anchor(wire));
        assertEquals("bag -77 in container 1 of object 4 at 10500,9600,0", key.toString());
    }

    @Test
    void nestedThreeBagsDeep() {
        byte[] corpse = bytes(square(DEAD_BODY, 10500, 9600, 1).putShort((short) 2));
        byte[] wire = ContainerWire.nested(corpse, List.of(1, 2, 3));
        ContainerKey key = ContainerWire.decode(wire);
        assertEquals(ContainerKey.nested(ContainerKey.corpse(10500, 9600, 1, 2), List.of(1, 2, 3)), key);
        assertEquals(List.of(1, 2, 3), key.bags());
        assertArrayEquals(corpse, ContainerWire.anchor(wire));
        assertEquals("bag 3 in bag 2 in bag 1 in corpse 2 at 10500,9600,1", key.toString());
    }

    @Test
    void nestedKeysCompareByChain() {
        assertEquals(ContainerWire.decode(ContainerWire.nested(CRATE, List.of(1, 2))),
                ContainerWire.decode(ContainerWire.nested(CRATE, List.of(1, 2))));
        assertNotEquals(ContainerWire.decode(ContainerWire.nested(CRATE, List.of(1, 2))),
                ContainerWire.decode(ContainerWire.nested(CRATE, List.of(2, 1))));
        assertNotEquals(ContainerWire.decode(ContainerWire.nested(CRATE, List.of(2))),
                ContainerWire.decode(ContainerWire.nested(CRATE, List.of(1, 2))));
    }

    @Test
    void nestedAnchorsAreOuterContainers() {
        byte[] bagOnFloor = bytes(square(WORLD_OBJECT, 10500, 9600, 0).putInt(5));
        byte[] part = bytes(square(VEHICLE, 0, 0, 0).putShort((short) 77).putShort((short) 5));
        byte[] object = bytes(square(ISO_OBJECT, 1, 2, 0).putShort((short) 3));
        assertEquals(ContainerKey.worldItem(10500, 9600, 0, 5),
                ContainerWire.decode(ContainerWire.nested(bagOnFloor, List.of(6))).anchor());
        assertEquals(ContainerKey.vehicle(77, 5),
                ContainerWire.decode(ContainerWire.nested(part, List.of(6, 7))).anchor());
        assertEquals(ContainerKey.object(1, 2, 0, 3),
                ContainerWire.decode(ContainerWire.nested(object, List.of(6))).anchor());
    }

    @Test
    void nestedRefusesAnchorsThatAreNotOuterContainers() {
        byte[] inventory = bytes(out().put(PLAYER_INVENTORY).putShort((short) 12).put((byte) 0));
        byte[] playerBag = bytes(out().put(INVENTORY_CONTAINER).putShort((short) 12).put((byte) 0).putInt(9));
        byte[] vehicleBag = bytes(square(OBJECT_IN_VEHICLE, 0, 0, 0).putShort((short) 77).putShort((short) 5)
                .putInt(9));
        byte[] floor = bytes(square(FLOOR, 10500, 9600, 0).put((byte) 0));
        byte[] nested = ContainerWire.nested(CRATE, List.of(1));
        for (byte[] anchor : List.of(inventory, playerBag, vehicleBag, floor, nested)) {
            assertThrows(IllegalArgumentException.class, () -> ContainerWire.nested(anchor, List.of(1)));
            byte[] wire = bytes(out().put((byte) ContainerWire.NESTED).put((byte) 1).putInt(1).put(anchor));
            assertThrows(ProtocolException.class, () -> ContainerWire.decode(wire));
        }
        assertThrows(IllegalArgumentException.class, () -> ContainerKey.nested(ContainerKey.player(1), List.of(1)));
    }

    @Test
    void nestedDepthIsBounded() {
        List<Integer> most = Collections.nCopies(ContainerKey.MAX_BAGS, 7);
        assertEquals(most, ContainerWire.decode(ContainerWire.nested(CRATE, most)).bags());
        List<Integer> tooMany = Collections.nCopies(ContainerKey.MAX_BAGS + 1, 7);
        assertThrows(IllegalArgumentException.class, () -> ContainerWire.nested(CRATE, tooMany));
        assertThrows(IllegalArgumentException.class, () -> ContainerWire.nested(CRATE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ContainerKey.nested(CRATE_KEY, tooMany));
        for (int count : new int[] {0, ContainerKey.MAX_BAGS + 1, -1}) {
            ByteBuffer b = out().put((byte) ContainerWire.NESTED).put((byte) count);
            for (int i = 0; i < Math.max(count, 0); i++) {
                b.putInt(7);
            }
            byte[] wire = bytes(b.put(CRATE));
            assertThrows(ProtocolException.class, () -> ContainerWire.decode(wire), "count " + count);
        }
    }

    @Test
    void nestedTruncatedOrTrailingIsRefused() {
        byte[] whole = ContainerWire.nested(CRATE, List.of(1, 2, 3));
        for (int length = 0; length < whole.length; length++) {
            byte[] part = Arrays.copyOf(whole, length);
            assertThrows(ProtocolException.class, () -> ContainerWire.decode(part), "length " + length);
        }
        byte[] longer = Arrays.copyOf(whole, whole.length + 1);
        assertThrows(ProtocolException.class, () -> ContainerWire.decode(longer));
    }

    @Test
    void anchorOfAnAddressThatIsNotNested() {
        assertThrows(ProtocolException.class, () -> ContainerWire.anchor(CRATE));
    }
}
