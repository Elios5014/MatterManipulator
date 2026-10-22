package com.recursive_pineapple.matter_manipulator.common.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;

class CopyIntegrationTests {

    @Test
    void replacementReusesEachExistingComponentOnlyOnceWithoutMutatingInputs() {
        RecordingInventory inventory = new RecordingInventory();
        Item item = new Item();
        ItemStack existing = new ItemStack(item, 3);
        ItemStack required = new ItemStack(item, 2);
        assertTrue(inventory.tryReplaceItems(Arrays.asList(required, new ItemStack(item)), Arrays.asList(existing)));
        assertTrue(inventory.requested.isEmpty());
        assertTrue(inventory.refunded.isEmpty());
        assertEquals(3, existing.stackSize);
        assertEquals(2, required.stackSize);
    }

    @Test
    void conduitRotationMovesSettingsAndNeverCopiesBuffers() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("energyStoredRF", 1000);
        tag.setTag("tank", new NBTTagCompound());
        tag.setInteger("playerID", 42);
        tag.setIntArray("connections", new int[] { 0, 1 });
        tag.setByteArray("conModes", new byte[] { 0, 1, 2, 3, 4, 5 });
        tag.setInteger("priority.EAST", 10);
        tag.setInteger("priority.WEST", 20);
        tag.setInteger("roundRobin", ForgeDirection.EAST.flag);
        EnderIOAnalysisResult.stripRuntimeState(tag);
        assertFalse(tag.hasKey("energyStoredRF"));
        assertFalse(tag.hasKey("tank"));
        assertFalse(tag.hasKey("playerID"));
        assertFalse(tag.hasKey("connections"));

        Transform mirror = new Transform();
        mirror.flipX = true;
        EnderIOAnalysisResult.transformSettings(tag, mirror);
        assertEquals(20, tag.getInteger("priority.EAST"));
        assertEquals(10, tag.getInteger("priority.WEST"));
        assertEquals(ForgeDirection.WEST.flag, tag.getInteger("roundRobin"));
        assertArrayEquals(new byte[] { 0, 1, 2, 3, 5, 4 }, tag.getByteArray("conModes"));
    }

    @Test
    void translocatorMirrorKeepsFiltersAndDoesNotModifyTheOriginal() {
        TranslocatorAnalysisResult source = new TranslocatorAnalysisResult();
        source.data = new NBTTagCompound();
        NBTTagCompound attachment = new NBTTagCompound();
        attachment.setBoolean("fast", true);
        NBTTagCompound filters = new NBTTagCompound();
        filters.setString("item", "filter configuration");
        attachment.setTag("filters", filters);
        source.data.setTag("atmt5", attachment);
        TranslocatorAnalysisResult copy = source.clone();
        Transform mirror = new Transform();
        mirror.flipX = true;
        copy.transform(mirror);
        assertTrue(source.data.hasKey("atmt5"));
        assertFalse(copy.data.hasKey("atmt5"));
        assertEquals(attachment, copy.data.getCompoundTag("atmt4"));
        copy.data.getCompoundTag("atmt4").setBoolean("fast", false);
        assertTrue(attachment.getBoolean("fast"));
    }

    private static class RecordingInventory implements IPseudoInventory {

        boolean succeed = true;
        List<BigItemStack> requested;
        List<BigItemStack> refunded = new ArrayList<>();

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            assertEquals(CONSUME_FUZZY, flags);
            requested = items;
            return BooleanObjectImmutablePair.of(succeed, Collections.emptyList());
        }

        @Override
        public void givePlayerItems(List<BigItemStack> items) {
            refunded.addAll(items);
        }

        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) {
            throw new AssertionError("No fluids should be involved in component accounting");
        }
    }
}
