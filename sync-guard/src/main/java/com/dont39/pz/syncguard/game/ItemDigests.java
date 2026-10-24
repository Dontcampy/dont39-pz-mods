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
 * stats and clothing weight derived from those; a food's actual weight (follows its name); a sharp weapon's maximum
 * damage (the packet sends it scaled); mod data pairs one side writes for itself (see {@code KAHLUA}). Values the
 * packets narrow (colour, poison) are hashed as they arrive. Main thread only: one scratch buffer.
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

        @Override
        public boolean ownPerSide(Object key, Object value) {
            if (!(key instanceof String name)) {
                return false;
            }
            return switch (name) {
                // InventoryItem.setCustomName(boolean) writes the item's name before the change, in each side's
                // language; ItemStats and SyncItemFields call it on every apply, so a snapshot rewrites it
                case "customName" -> true;
                // RPGInventory's loot search marks items the client's player has seen (LootSearchRules.reveal)
                case "RPGInventoryDiscovered" -> true;
                // RPGInventory's client rewrites a bag's rules from its cache of the server's answer, with empty
                // tables where the server's record has none (TransferConfig.lua)
                case "RPGInventoryTransferRules" -> true;
                // Clothing.getUsedDelta() writes 0 when absent, on whichever side reads it (client Lua and
                // tooltips); a value set through setUsedDelta still counts
                case "usedDelta" -> value instanceof Double d && d == 0;
                default -> false;
            };
        }
    };

    private ItemDigests() {
    }

    private static boolean on(Checked field) {
        return CHECKED.contains(field);
    }

    /** The digest to use for an item, given its own. */
    @FunctionalInterface
    public interface ItemDigest {
        long of(int itemId, long own);
    }

    /** Bags' contents are their own containers. */
    public static long container(ItemContainer container) {
        return container(container, (id, own) -> own);
    }

    /** With {@code digest} deciding each item's digest from its own. */
    public static long container(ItemContainer container, ItemDigest digest) {
        ContainerDigest d = new ContainerDigest();
        for (InventoryItem item : container.getItems()) {
            d.add(item.getID(), item.getFullType(), digest.of(item.getID(), item(item)));
        }
        return d.get();
    }

    public static long item(InventoryItem item) {
        Group all = new Group(null, "");
        write(item, field -> all);
        return all.digest.get();
    }

    /** One item's digest fields for the field diff: each group's hash, and every value by name. */
    public record Fields(Map<String, Long> hashes, Map<String, String> values) {
    }

    /** Mod data tables deeper than this are described by their hash. */
    private static final int DESCRIBED_DEPTH = 3;

    /**
     * The same values as {@link #item}, hashed per field group instead of into one digest, and kept by name as
     * {@code group.field} (mod data as {@code modData.<key path>}), for {@code -Dsyncguard.debugFieldDiff}.
     */
    public static Fields fields(InventoryItem item) {
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        write(item, field -> groups.computeIfAbsent(field, k -> new Group(values, k)));
        Map<String, Long> hashes = new LinkedHashMap<>();
        groups.forEach((field, g) -> hashes.put(field, g.digest.get()));
        return new Fields(hashes, values);
    }

    /** One group's digest; while describing, also each value by name. */
    private static final class Group {
        final Digest digest = new Digest();
        private final Map<String, String> values;
        private final String prefix;

        /** {@code values}: null when only hashing. */
        Group(Map<String, String> values, String group) {
            this.values = values;
            this.prefix = values == null ? null : group + ".";
        }

        boolean describing() {
            return values != null;
        }

        void note(String name, String value) {
            values.put(prefix + name, value);
        }

        Group i(String name, int v) {
            digest.putInt(v);
            if (values != null) {
                note(name, Integer.toString(v));
            }
            return this;
        }

        Group l(String name, long v) {
            digest.putLong(v);
            if (values != null) {
                note(name, String.format("%016x", v));
            }
            return this;
        }

        Group f(String name, float v) {
            digest.putFloat(v);
            if (values != null) {
                note(name, Float.toString(v));
            }
            return this;
        }

        Group b(String name, boolean v) {
            digest.putBoolean(v);
            if (values != null) {
                note(name, Boolean.toString(v));
            }
            return this;
        }

        Group s(String name, String v) {
            digest.putString(v);
            if (values != null) {
                note(name, v == null ? "null" : '"' + v + '"');
            }
            return this;
        }

        Group bytes(String name, byte[] bytes, int length) {
            digest.putBytes(bytes, 0, length);
            if (values != null) {
                note(name, String.format("%d bytes #%016x", length, new Digest().putBytes(bytes, 0, length).get()));
            }
            return this;
        }
    }

    /** Where each field's values go: one group for all, or one per field. */
    @FunctionalInterface
    private interface Sink {
        Group at(String field);
    }

    private static void write(InventoryItem item, Sink out) {
        out.at("type").s("fullType", item.getFullType());
        if (on(Checked.CONDITION)) {
            out.at("condition").i("condition", item.getCondition());
        }
        if (on(Checked.HEAD_CONDITION)) {
            Group g = out.at("headCondition");
            g.b("has", item.hasHeadCondition());
            if (item.hasHeadCondition()) {
                g.i("value", item.getHeadCondition());
            }
        }
        out.at("remoteControlId").i("id", item.getRemoteControlID());
        if (on(Checked.USES) && !item.isActivated()) {
            Group g = out.at("uses");
            g.i("current", item.getCurrentUses());
            if (item instanceof DrainableComboItem) {
                g.f("float", item.getCurrentUsesFloat());
            }
        }
        if (on(Checked.AMMO)) {
            out.at("ammo").i("count", item.getCurrentAmmoCount());
        }
        out.at("repaired").i("times", item.getHaveBeenRepaired());
        // clothing's weight follows its wetness, which is left out; see food() for a food's
        if (on(Checked.WEIGHT) && !(item instanceof Clothing) && !(item instanceof Food f && nameWeighted(f))) {
            out.at("weight").f("weight", item.getWeight());
        }
        out.at("favorite").b("favorite", item.isFavorite());
        Group name = out.at("customName");
        name.b("custom", item.isCustomName());
        if (item.isCustomName()) {
            name.s("name", item.getName());
        }
        Group color = out.at("customColor");
        color.b("custom", item.isCustomColor());
        if (item.isCustomColor()) {
            // SyncItemFields carries it as 8 bits per channel
            color.i("abgr", Color.colorToABGR(item.getColor()));
        }
        if (on(Checked.BLOOD) && (item instanceof Clothing || item instanceof HandWeapon)) {
            out.at("blood").f("level", item.getBloodLevel());
        }
        Group sharpness = out.at("sharpness");
        sharpness.b("has", item.hasSharpness());
        if (on(Checked.SHARPNESS) && item.hasSharpness()) {
            sharpness.f("value", item.getSharpness());
        }
        if (on(Checked.MOD_DATA)) {
            Group g = out.at("modData");
            KahluaTable table = item.hasModData() ? item.getModData() : null;
            g.digest.putLong(table != null ? LuaTableHash.hash(table, KAHLUA) : LuaTableHash.EMPTY);
            if (g.describing() && table != null) {
                LuaTableHash.describe(table, KAHLUA, DESCRIBED_DEPTH, g::note);
            }
        }
        if (on(Checked.WET)) {
            out.at("wet").b("wet", item.isWet());
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
    private static void fluids(Group g, FluidContainer fluids) {
        g.b("has", fluids != null);
        if (fluids == null) {
            return;
        }
        FLUIDS.clear();
        try {
            fluids.save(FLUIDS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        g.bytes("saved", FLUIDS.array(), FLUIDS.position());
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
        out.at("patches").l("set", patches.get());
        ItemVisual visual = clothing.getVisual();
        Group g = out.at("visual");
        g.b("has", on(Checked.VISUAL) && visual != null);
        if (on(Checked.VISUAL) && visual != null) {
            for (int i = 0; i < parts; i++) {
                BloodBodyPartType part = BloodBodyPartType.FromIndex(i);
                String p = part.name();
                g.f(p + ".basic", visual.getBasicPatch(part)).f(p + ".denim", visual.getDenimPatch(part))
                        .f(p + ".leather", visual.getLeatherPatch(part)).f(p + ".hole", visual.getHole(part))
                        .f(p + ".dirt", visual.getDirt(part)).f(p + ".blood", visual.getBlood(part));
            }
        }
    }

    // Food.getWeight() is getActualWeight() then
    private static boolean nameWeighted(Food food) {
        return food.getReplaceOnUse() != null;
    }

    // thirst, unhappiness, boredom, stress and food sickness are left out: their getters scale them by age, heat,
    // freezing and cooking, and ItemStats stores the server's scaled value as the client's base. So is the actual
    // weight: getActualWeightUnmodded() returns 0 for an item whose name equals its full type, as the dedicated
    // server has it for some mod foods while the client has a translated name, until an ItemStats copies the
    // server's name over
    private static void food(Sink out, Food food) {
        out.at("food").f("hungChange", food.getHungChange())
                .f("calories", food.getCalories()).f("carbohydrates", food.getCarbohydrates())
                .f("lipids", food.getLipids()).f("proteins", food.getProteins())
                .i("fluReduction", food.getFluReduction()).f("painReduction", food.getPainReduction())
                .f("endChange", food.getEndChange())
                .f("fatigueChange", food.getFatigueChange())
                // ItemStats writes both as a byte, and leaves out a detection level of -1 ("none"), which the
                // client then reads as 0
                .i("poisonPower", (byte) food.getPoisonPower())
                .i("poisonDetectionLevel", food.getPoisonDetectionLevel() == -1 ? 0
                        : (byte) food.getPoisonDetectionLevel())
                .b("alcoholic", food.isAlcoholic())
                .f("baseHunger", food.getBaseHunger())
                .f("minutesToCook", food.getMinutesToCook()).f("minutesToBurn", food.getMinutesToBurn())
                .b("tainted", food.isTainted());
        strings(out.at("extraItems"), food.getExtraItems());
        strings(out.at("spices"), food.getSpices());
        if (on(Checked.COOKED_BURNT)) {
            out.at("cookedBurnt").b("cooked", food.isCooked()).b("burnt", food.isBurnt());
        }
    }

    // none and empty alike: ItemStats creates an empty list on the client where the server may have none
    private static void strings(Group g, List<String> list) {
        List<String> values = list != null ? list : List.of();
        g.i("size", values.size());
        for (int i = 0; i < values.size(); i++) {
            g.s(Integer.toString(i), values.get(i));
        }
    }

    private static void literature(Sink out, Literature literature) {
        if (on(Checked.PAGES)) {
            out.at("pages").i("read", literature.getAlreadyReadPages());
        }
        Map<Integer, String> pages = literature.getCustomPages();
        Group g = out.at("customPages");
        g.b("has", pages != null);
        if (pages != null) {
            Unordered u = new Unordered();
            pages.forEach((page, text) -> u.add(new Digest().putInt(page).putString(text).get()));
            g.l("set", u.get());
        }
    }

    private static void weapon(Sink out, HandWeapon weapon) {
        if (on(Checked.AMMO)) {
            out.at("weaponAmmo").b("roundChambered", weapon.isRoundChambered())
                    .b("containsClip", weapon.isContainsClip()).i("spentRounds", weapon.getSpentRoundCount())
                    .b("spentRoundChambered", weapon.isSpentRoundChambered()).b("jammed", weapon.isJammed());
        }
        Group stats = out.at("weaponStats").f("maxRange", weapon.getMaxRange())
                .f("minRangeRanged", weapon.getMinRangeRanged())
                .i("clipSize", weapon.getClipSize()).i("reloadTime", weapon.getReloadTime())
                .i("recoilDelay", weapon.getRecoilDelay()).i("aimingTime", weapon.getAimingTime())
                .i("hitChance", weapon.getHitChance()).f("minAngle", weapon.getMinAngle())
                .f("minDamage", weapon.getMinDamage());
        // getMaxDamage() scales by sharpness; SyncHandWeaponFields sends the scaled value, which the client stores
        // as its base and scales again
        if (!weapon.hasSharpness()) {
            stats.f("maxDamage", weapon.getMaxDamage());
        }
        PARTS.clear();
        weapon.getAllWeaponParts(PARTS);
        Unordered parts = new Unordered();
        for (WeaponPart part : PARTS) {
            parts.add(new Digest().putString(part.getFullType()).get());
        }
        PARTS.clear();
        out.at("weaponParts").l("set", parts.get());
    }
}
