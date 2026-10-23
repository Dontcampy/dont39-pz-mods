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
import java.util.LinkedHashMap;
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
 * save, not in a field packet); a name that is not custom (each side translates it into its own language); food
 * stats and clothing weight derived from those.
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
        write(item, field -> d);
        return d.get();
    }

    /**
     * The same values as {@link #item}, hashed per field group instead of into one digest, for the per-field diff
     * of {@code -Dsyncguard.debugFieldDiff}. Groups are named as in this class.
     */
    public static Map<String, Long> fields(InventoryItem item) {
        Map<String, Digest> groups = new LinkedHashMap<>();
        write(item, field -> groups.computeIfAbsent(field, k -> new Digest()));
        Map<String, Long> out = new LinkedHashMap<>();
        groups.forEach((field, d) -> out.put(field, d.get()));
        return out;
    }

    /** Where each field's values go: one digest for all, or one per field. */
    @FunctionalInterface
    private interface Sink {
        Digest at(String field);
    }

    private static void write(InventoryItem item, Sink out) {
        out.at("type").putString(item.getFullType());
        if (on(Checked.CONDITION)) {
            out.at("condition").putInt(item.getCondition());
        }
        if (on(Checked.HEAD_CONDITION)) {
            Digest d = out.at("headCondition");
            d.putBoolean(item.hasHeadCondition());
            if (item.hasHeadCondition()) {
                d.putInt(item.getHeadCondition());
            }
        }
        out.at("remoteControlId").putInt(item.getRemoteControlID());
        if (on(Checked.USES) && !item.isActivated()) {
            Digest d = out.at("uses");
            d.putInt(item.getCurrentUses());
            if (item instanceof DrainableComboItem) {
                d.putFloat(item.getCurrentUsesFloat());
            }
        }
        if (on(Checked.AMMO)) {
            out.at("ammo").putInt(item.getCurrentAmmoCount());
        }
        out.at("repaired").putInt(item.getHaveBeenRepaired());
        // clothing's weight follows its wetness, which is left out
        if (on(Checked.WEIGHT) && !(item instanceof Clothing)) {
            out.at("weight").putFloat(item.getWeight());
        }
        out.at("favorite").putBoolean(item.isFavorite());
        Digest name = out.at("customName");
        name.putBoolean(item.isCustomName());
        if (item.isCustomName()) {
            name.putString(item.getName());
        }
        Digest color = out.at("customColor");
        color.putBoolean(item.isCustomColor());
        if (item.isCustomColor()) {
            Color c = item.getColor();
            color.putFloat(c.r).putFloat(c.g).putFloat(c.b).putFloat(c.a);
        }
        if (on(Checked.BLOOD) && (item instanceof Clothing || item instanceof HandWeapon)) {
            out.at("blood").putFloat(item.getBloodLevel());
        }
        Digest sharpness = out.at("sharpness");
        sharpness.putBoolean(item.hasSharpness());
        if (on(Checked.SHARPNESS) && item.hasSharpness()) {
            sharpness.putFloat(item.getSharpness());
        }
        if (on(Checked.MOD_DATA)) {
            out.at("modData").putLong(item.hasModData() ? LuaTableHash.hash(item.getModData(), KAHLUA)
                    : LuaTableHash.EMPTY);
        }
        if (on(Checked.WET)) {
            out.at("wet").putBoolean(item.isWet());
        }
        fluids(out.at("fluids"), item.getFluidContainer());
        if (item instanceof Clothing clothing) {
            clothing(out, clothing);
        }
        if (item instanceof Food food) {
            food(out, food);
        }
        if (item instanceof Literature literature) {
            literature(out, literature);
        }
        if (item instanceof HandWeapon weapon) {
            weapon(out, weapon);
        }
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

    private static void clothing(Sink out, Clothing clothing) {
        int parts = BloodBodyPartType.MAX.index();
        Unordered patches = new Unordered();
        for (int i = 0; i < parts; i++) {
            Clothing.ClothingPatch patch = clothing.getPatchType(BloodBodyPartType.FromIndex(i));
            if (patch != null) {
                patches.add(Digest.of(i, patch.tailorLvl, patch.fabricType, patch.hasHole ? 1 : 0));
            }
        }
        out.at("patches").putLong(patches.get());
        ItemVisual visual = clothing.getVisual();
        Digest d = out.at("visual");
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

    // thirst, unhappiness, boredom, stress and food sickness are left out: their getters scale them by age, heat,
    // freezing and cooking, and ItemStats stores the server's scaled value as the client's base
    private static void food(Sink out, Food food) {
        out.at("food").putFloat(food.getHungChange()).putFloat(food.getActualWeightUnmodded())
                .putFloat(food.getCalories()).putFloat(food.getCarbohydrates()).putFloat(food.getLipids())
                .putFloat(food.getProteins())
                .putInt(food.getFluReduction()).putFloat(food.getPainReduction())
                .putFloat(food.getEndChange())
                .putFloat(food.getFatigueChange()).putInt(food.getPoisonPower())
                .putInt(food.getPoisonDetectionLevel()).putBoolean(food.isAlcoholic()).putFloat(food.getBaseHunger())
                .putFloat(food.getMinutesToCook()).putFloat(food.getMinutesToBurn()).putBoolean(food.isTainted());
        strings(out.at("extraItems"), food.getExtraItems());
        strings(out.at("spices"), food.getSpices());
        if (on(Checked.COOKED_BURNT)) {
            out.at("cookedBurnt").putBoolean(food.isCooked()).putBoolean(food.isBurnt());
        }
    }

    private static void strings(Digest d, List<String> list) {
        d.putBoolean(list != null);
        if (list != null) {
            d.putInt(list.size());
            list.forEach(d::putString);
        }
    }

    private static void literature(Sink out, Literature literature) {
        if (on(Checked.PAGES)) {
            out.at("pages").putInt(literature.getAlreadyReadPages());
        }
        Map<Integer, String> pages = literature.getCustomPages();
        Digest d = out.at("customPages");
        d.putBoolean(pages != null);
        if (pages != null) {
            Unordered u = new Unordered();
            pages.forEach((page, text) -> u.add(new Digest().putInt(page).putString(text).get()));
            d.putLong(u.get());
        }
    }

    private static void weapon(Sink out, HandWeapon weapon) {
        if (on(Checked.AMMO)) {
            out.at("weaponAmmo").putBoolean(weapon.isRoundChambered()).putBoolean(weapon.isContainsClip())
                    .putInt(weapon.getSpentRoundCount()).putBoolean(weapon.isSpentRoundChambered())
                    .putBoolean(weapon.isJammed());
        }
        out.at("weaponStats").putFloat(weapon.getMaxRange()).putFloat(weapon.getMinRangeRanged())
                .putInt(weapon.getClipSize()).putInt(weapon.getReloadTime()).putInt(weapon.getRecoilDelay())
                .putInt(weapon.getAimingTime())
                .putInt(weapon.getHitChance()).putFloat(weapon.getMinAngle()).putFloat(weapon.getMinDamage())
                .putFloat(weapon.getMaxDamage());
        PARTS.clear();
        weapon.getAllWeaponParts(PARTS);
        Unordered parts = new Unordered();
        for (WeaponPart part : PARTS) {
            parts.add(new Digest().putString(part.getFullType()).get());
        }
        PARTS.clear();
        out.at("weaponParts").putLong(parts.get());
    }
}
