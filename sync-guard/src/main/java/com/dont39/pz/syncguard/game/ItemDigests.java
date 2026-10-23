package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.ContainerDigest;
import com.dont39.pz.syncguard.protocol.Digest;
import com.dont39.pz.syncguard.protocol.LuaTableHash;
import com.dont39.pz.syncguard.protocol.Unordered;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.characterTextures.BloodBodyPartType;
import zombie.core.Color;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.entity.components.fluids.FluidContainer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.Literature;
import zombie.inventory.types.WeaponPart;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The digests both sides compare: over the item state the game's field packets carry and the client takes only from
 * them (SyncItemFields, ItemStats, SyncItemModData, SyncHandWeaponFields, as their {@code setData} reads it).
 * <p>
 * Left out, each because the two sides legitimately differ or a field payload cannot repair it: heat and item heat,
 * age, freezing time and frozen (simulated on both sides, never sent; the client's inventory pane ages food itself);
 * fertilized and its time (the client's freezing update clears it); cooking time (Food.update sends before it
 * advances it); wet cooldown (counts down on both sides); clothing wetness and dirtiness (own packet, and the client
 * updates them); attached slot, slot type and model (the hotbar resets them on the client without a sync); tainted
 * water (setData never sets it); activated, key id, stash map, infection, world scale and rotations (only in the full
 * save, not in a field packet); a name that is not custom (each side translates it into its own language).
 * Main thread only: one scratch buffer.
 */
public final class ItemDigests {
    /**
     * Fields that may differ between the sides in normal play for reasons not settled from the bytecode; the in-game
     * per-field diff run decides each. All are in until it drops one; both sides must agree, so a change bumps
     * {@code Protocol.VERSION}.
     */
    public enum Checked {
        /** Ranged weapon wear on the client is not proven; break-when-wet clothing breaks in the client's pane. */
        CONDITION,
        HEAD_CONDITION,
        /** Activated drainables drain on both sides; left out while activated in any case. */
        USES,
        /** Fired and reloaded on the client first, in flight to the server at the frame. */
        AMMO,
        /** Combat may bloody weapons and clothing on the client. */
        BLOOD,
        /** Clothing blood, dirt and holes, as for {@link #BLOOD}. */
        VISUAL,
        /** May be derived from contents or fluids. */
        WEIGHT,
        /** The reading path was not traced. */
        PAGES,
        /** Melee wear's side is not proven. */
        SHARPNESS,
        /** Mods may write item mod data on the client without a sync. */
        MOD_DATA,
        /** Set after the minute's ItemStats; the next minute's carries it. */
        COOKED_BURNT,
        /** Flips on both sides when the cooldown ends. */
        WET
    }

    private static final Set<Checked> CHECKED = EnumSet.allOf(Checked.class);
    private static final ByteBuffer FLUIDS = ByteBuffer.allocate(64 * 1024);
    private static final List<WeaponPart> PARTS = new ArrayList<>();

    private static final LuaTableHash.Tables KAHLUA = new LuaTableHash.Tables() {
        @Override
        public boolean isTable(Object value) {
            return value instanceof KahluaTable;
        }

        @Override
        public void forEach(Object table, BiConsumer<Object, Object> pair) {
            KahluaTableIterator it = ((KahluaTable) table).iterator();
            while (it.advance()) {
                pair.accept(it.getKey(), it.getValue());
            }
        }

        @Override
        public boolean saved(Object key, Object value) {
            return KahluaTableImpl.canSave(key, value);
        }
    };

    private ItemDigests() {
    }

    private static boolean on(Checked field) {
        return CHECKED.contains(field);
    }

    /** Bags' contents are their own containers. */
    public static long container(ItemContainer container) {
        ContainerDigest d = new ContainerDigest();
        for (InventoryItem item : container.getItems()) {
            d.add(item.getID(), item.getFullType(), item(item));
        }
        return d.get();
    }

    public static long item(InventoryItem item) {
        Digest d = new Digest();
        d.putString(item.getFullType());
        if (on(Checked.CONDITION)) {
            d.putInt(item.getCondition());
        }
        if (on(Checked.HEAD_CONDITION)) {
            d.putBoolean(item.hasHeadCondition());
            if (item.hasHeadCondition()) {
                d.putInt(item.getHeadCondition());
            }
        }
        d.putInt(item.getRemoteControlID());
        if (on(Checked.USES) && !item.isActivated()) {
            d.putInt(item.getCurrentUses());
            if (item instanceof DrainableComboItem) {
                d.putFloat(item.getCurrentUsesFloat());
            }
        }
        if (on(Checked.AMMO)) {
            d.putInt(item.getCurrentAmmoCount());
        }
        d.putInt(item.getHaveBeenRepaired());
        if (on(Checked.WEIGHT)) {
            d.putFloat(item.getWeight());
        }
        d.putBoolean(item.isFavorite());
        d.putBoolean(item.isCustomName());
        if (item.isCustomName()) {
            d.putString(item.getName());
        }
        d.putBoolean(item.isCustomColor());
        if (item.isCustomColor()) {
            Color c = item.getColor();
            d.putFloat(c.r).putFloat(c.g).putFloat(c.b).putFloat(c.a);
        }
        if (on(Checked.BLOOD) && (item instanceof Clothing || item instanceof HandWeapon)) {
            d.putFloat(item.getBloodLevel());
        }
        d.putBoolean(item.hasSharpness());
        if (on(Checked.SHARPNESS) && item.hasSharpness()) {
            d.putFloat(item.getSharpness());
        }
        if (on(Checked.MOD_DATA)) {
            d.putLong(item.hasModData() ? LuaTableHash.hash(item.getModData(), KAHLUA) : LuaTableHash.EMPTY);
        }
        if (on(Checked.WET)) {
            d.putBoolean(item.isWet());
        }
        fluids(d, item.getFluidContainer());
        if (item instanceof Clothing clothing) {
            clothing(d, clothing);
        }
        if (item instanceof Food food) {
            food(d, food);
        }
        if (item instanceof Literature literature) {
            literature(d, literature);
        }
        if (item instanceof HandWeapon weapon) {
            weapon(d, weapon);
        }
        return d.get();
    }

    // as SyncItemFields carries it: the container's own save
    private static void fluids(Digest d, FluidContainer fluids) {
        d.putBoolean(fluids != null);
        if (fluids == null) {
            return;
        }
        FLUIDS.clear();
        try {
            fluids.save(FLUIDS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        d.putBytes(FLUIDS.array(), 0, FLUIDS.position());
    }

    private static void clothing(Digest d, Clothing clothing) {
        int parts = BloodBodyPartType.MAX.index();
        Unordered patches = new Unordered();
        for (int i = 0; i < parts; i++) {
            Clothing.ClothingPatch patch = clothing.getPatchType(BloodBodyPartType.FromIndex(i));
            if (patch != null) {
                patches.add(Digest.of(i, patch.tailorLvl, patch.fabricType, patch.hasHole ? 1 : 0));
            }
        }
        d.putLong(patches.get());
        ItemVisual visual = clothing.getVisual();
        d.putBoolean(on(Checked.VISUAL) && visual != null);
        if (on(Checked.VISUAL) && visual != null) {
            for (int i = 0; i < parts; i++) {
                BloodBodyPartType part = BloodBodyPartType.FromIndex(i);
                d.putFloat(visual.getBasicPatch(part)).putFloat(visual.getDenimPatch(part))
                        .putFloat(visual.getLeatherPatch(part)).putFloat(visual.getHole(part))
                        .putFloat(visual.getDirt(part)).putFloat(visual.getBlood(part));
            }
        }
    }

    private static void food(Digest d, Food food) {
        d.putFloat(food.getHungChange()).putFloat(food.getActualWeightUnmodded()).putFloat(food.getCalories())
                .putFloat(food.getCarbohydrates()).putFloat(food.getLipids()).putFloat(food.getProteins())
                .putFloat(food.getThirstChange()).putInt(food.getFluReduction()).putFloat(food.getPainReduction())
                .putFloat(food.getEndChange()).putInt(food.getFoodSicknessChange()).putFloat(food.getStressChange())
                .putFloat(food.getFatigueChange()).putFloat(food.getUnhappyChange())
                .putFloat(food.getBoredomChange()).putInt(food.getPoisonPower())
                .putInt(food.getPoisonDetectionLevel()).putBoolean(food.isAlcoholic()).putFloat(food.getBaseHunger())
                .putFloat(food.getMinutesToCook()).putFloat(food.getMinutesToBurn()).putBoolean(food.isTainted());
        strings(d, food.getExtraItems());
        strings(d, food.getSpices());
        if (on(Checked.COOKED_BURNT)) {
            d.putBoolean(food.isCooked()).putBoolean(food.isBurnt());
        }
    }

    private static void strings(Digest d, List<String> list) {
        d.putBoolean(list != null);
        if (list != null) {
            d.putInt(list.size());
            list.forEach(d::putString);
        }
    }

    private static void literature(Digest d, Literature literature) {
        if (on(Checked.PAGES)) {
            d.putInt(literature.getAlreadyReadPages());
        }
        Map<Integer, String> pages = literature.getCustomPages();
        d.putBoolean(pages != null);
        if (pages != null) {
            Unordered u = new Unordered();
            pages.forEach((page, text) -> u.add(new Digest().putInt(page).putString(text).get()));
            d.putLong(u.get());
        }
    }

    private static void weapon(Digest d, HandWeapon weapon) {
        if (on(Checked.AMMO)) {
            d.putBoolean(weapon.isRoundChambered()).putBoolean(weapon.isContainsClip())
                    .putInt(weapon.getSpentRoundCount()).putBoolean(weapon.isSpentRoundChambered())
                    .putBoolean(weapon.isJammed());
        }
        d.putFloat(weapon.getMaxRange()).putFloat(weapon.getMinRangeRanged()).putInt(weapon.getClipSize())
                .putInt(weapon.getReloadTime()).putInt(weapon.getRecoilDelay()).putInt(weapon.getAimingTime())
                .putInt(weapon.getHitChance()).putFloat(weapon.getMinAngle()).putFloat(weapon.getMinDamage())
                .putFloat(weapon.getMaxDamage());
        PARTS.clear();
        weapon.getAllWeaponParts(PARTS);
        Unordered parts = new Unordered();
        for (WeaponPart part : PARTS) {
            parts.add(new Digest().putString(part.getFullType()).get());
        }
        PARTS.clear();
        d.putLong(parts.get());
    }
}
