package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;

import net.minecraftforge.fluids.FluidStack;

import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.ItemId;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;

/**
 * Something that can accept and provide items/fluids.
 */
public interface IPseudoInventory {

    /** Items will not actually be consumed. */
    public static final int CONSUME_SIMULATED = 0b1;
    /** Items will be fuzzily-matched. Ignores NBT and ignores damage for items without subitems */
    public static final int CONSUME_FUZZY = 0b10;
    /** Not all items must be extracted. */
    public static final int CONSUME_PARTIAL = 0b100;
    /** Creative mode infinite supply will be ignored, but not 111 stacks. */
    public static final int CONSUME_IGNORE_CREATIVE = 0b1000;
    /** Only consume real items (extractions while planning will always fail). */
    public static final int CONSUME_REAL_ONLY = 0b10000;

    /**
     * Atomically extracts items from this pseudo inventory.
     * The returned list is guaranteed to at minimum be equal to the items param.
     * If the extraction succeeded and partial mode wasn't enabled, extraneous items will not be extracted and the
     * returned list will contain the same items as the request.
     * If fuzzy mode is enabled there may be several stacks with different tags (and damages where relevant), but every
     * stackable item will be merged into the same BigItemStack.
     *
     * @param items The list of items to extract.
     * @param flags The flags (see {@link IPseudoInventory#CONSUME_SIMULATED}, {@link IPseudoInventory#CONSUME_FUZZY},
     *        etc).
     * @return Key = whether the extract was successful. Value = the list of items extracted (only relevant for fuzzy
     *         mode).
     */
    public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags);

    /**
     * Consumes a set of items.
     *
     * @return True when the items were successfully consumed.
     */
    public default boolean tryConsumeItems(ItemStack... items) {
        return tryConsumeItems(MMUtils.mapToList(items, BigItemStack::create), 0).leftBoolean();
    }

    /** Pays only for missing components and refunds surplus ones. The caller must replace the old components on success. */
    public default boolean tryReplaceItems(List<ItemStack> required, List<ItemStack> existing) {
        List<ItemStack> surplus = new ArrayList<>();
        for (ItemStack stack : existing) {
            if (stack == null) return false;
            surplus.add(stack.copy());
        }
        Map<ItemId, BigItemStack> missing = new HashMap<>();
        for (ItemStack stack : required) {
            if (stack == null) return false;
            int amount = stack.stackSize;
            for (ItemStack available : surplus) {
                if (!available.isItemEqual(stack) || !ItemStack.areItemStackTagsEqual(available, stack)) continue;
                int used = Math.min(amount, available.stackSize);
                amount -= used;
                available.stackSize -= used;
            }
            if (amount > 0) {
                ItemId id = ItemId.create(stack.getItem(), stack.getItemDamage(), stack.getTagCompound());
                BigItemStack request = missing.computeIfAbsent(id, key -> {
                    BigItemStack item = new BigItemStack();
                    item.item = stack.getItem();
                    item.meta = stack.getItemDamage();
                    item.tag = stack.getTagCompound() == null ? null : (net.minecraft.nbt.NBTTagCompound) stack.getTagCompound().copy();
                    item.id = key;
                    return item;
                });
                request.incStackSize(amount);
            }
        }
        List<BigItemStack> requests = new ArrayList<>(missing.values());
        if (!tryConsumeItems(requests, 0).leftBoolean()) return false;
        for (ItemStack stack : surplus) {
            if (stack.stackSize > 0) givePlayerItems(stack);
        }
        return true;
    }

    public void givePlayerItems(List<BigItemStack> items);

    public default void givePlayerItems(ItemStack... items) {
        givePlayerItems(MMUtils.mapToList(items, BigItemStack::create));
    }

    public void givePlayerFluids(List<BigFluidStack> fluids);

    public default void givePlayerFluids(FluidStack... fluids) {
        givePlayerFluids(MMUtils.mapToList(fluids, BigFluidStack::create));
    }
}
