package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

import java.util.ArrayList;

/**
 * The ItemContainer methods that add items to or remove them from its list themselves; the others (DoAddItem,
 * AddItems of items, SpawnItem of an item, removeItemWithID, takeItemsFrom, ...) call one of these. A change by any
 * route counts, as a mod may sync it its own way (D7). Not seen: AddItem(String) and AddItem(String, float), which
 * Zabric cannot hook apart from their longer overloads, except through AddItems(String, int) and addItem(ItemKey);
 * code that edits the public item list directly.
 */
@Hooks(value = ItemContainer.class, gate = Gate.class)
public final class ContentHooks {
    private ContentHooks() {
    }

    // also takes the item out of its old container through Remove
    @Inject(method = "AddItem(Lzombie/inventory/InventoryItem;)Lzombie/inventory/InventoryItem;", at = @At("HEAD"))
    public static void addItem(@Self ItemContainer container, CallbackInfoReturnable<InventoryItem> ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "AddItemBlind(Lzombie/inventory/InventoryItem;)Lzombie/inventory/InventoryItem;",
            at = @At("HEAD"))
    public static void addItemBlind(@Self ItemContainer container, CallbackInfoReturnable<InventoryItem> ci) {
        ServerSync.contentChanged(container);
    }

    // AddItem(String) for each
    @Inject(method = "AddItems(Ljava/lang/String;I)Ljava/util/ArrayList;", at = @At("HEAD"))
    public static void addItemsOfType(@Self ItemContainer container,
            CallbackInfoReturnable<ArrayList<InventoryItem>> ci) {
        ServerSync.contentChanged(container);
    }

    // AddItem(String)
    @Inject(method = "addItem(Lzombie/scripting/objects/ItemKey;)Lzombie/inventory/InventoryItem;", at = @At("HEAD"))
    public static void addItemOfKey(@Self ItemContainer container, CallbackInfoReturnable<InventoryItem> ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "AddItem(Ljava/lang/String;FZ)Z", at = @At("HEAD"))
    public static void addItemWithUsesSynced(@Self ItemContainer container, CallbackInfoReturnable<Boolean> ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "Remove(Lzombie/inventory/InventoryItem;)V", at = @At("HEAD"))
    public static void remove(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "DoRemoveItem(Lzombie/inventory/InventoryItem;)V", at = @At("HEAD"))
    public static void doRemoveItem(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "Remove(Ljava/lang/String;)V", at = @At("HEAD"))
    public static void removeOfType(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "Remove(Lzombie/scripting/objects/ItemType;)Lzombie/inventory/InventoryItem;", at = @At("HEAD"))
    public static void removeOfItemType(@Self ItemContainer container, CallbackInfoReturnable<InventoryItem> ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "RemoveAll(Ljava/lang/String;I)Ljava/util/ArrayList;", at = @At("HEAD"))
    public static void removeAll(@Self ItemContainer container,
            CallbackInfoReturnable<ArrayList<InventoryItem>> ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "RemoveOneOf(Ljava/lang/String;Z)Lzombie/inventory/InventoryItem;", at = @At("HEAD"))
    public static void removeOneOf(@Self ItemContainer container, CallbackInfoReturnable<InventoryItem> ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "clear()V", at = @At("HEAD"))
    public static void clear(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "removeAllItems()V", at = @At("HEAD"))
    public static void removeAllItems(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "setItems(Ljava/util/ArrayList;)V", at = @At("HEAD"))
    public static void setItems(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }

    @Inject(method = "emptyIt()V", at = @At("HEAD"))
    public static void emptyIt(@Self ItemContainer container, CallbackInfo ci) {
        ServerSync.contentChanged(container);
    }
}
