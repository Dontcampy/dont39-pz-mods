package com.dont39.pz.syncguard.protocol;

/**
 * An item by its id, with the container it was seen in: the server resolves an item only through its container, and
 * a moved item shows up as gone here and changed in its new container.
 */
public record ItemKey(ContainerKey container, int itemId) implements Key {
    @Override
    public String toString() {
        return "item " + itemId + " in " + container;
    }
}
