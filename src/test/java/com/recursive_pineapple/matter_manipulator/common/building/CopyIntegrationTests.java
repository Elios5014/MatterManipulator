package com.recursive_pineapple.matter_manipulator.common.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import org.junit.jupiter.api.Test;

import codechicken.translocator.TileItemTranslocator;
import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;

class CopyIntegrationTests {

    @Test
    void newTranslocatorPlanLeavesOneBaseItemToNormalBlockPlacement() {
        Item base = new Item();
        Item modifier = new Item();
        TranslocatorAnalysisResult source = translocatorWithTwoAttachments(base, modifier);
        RecordingContext context = new RecordingContext();

        assertTrue(source.getRequiredItemsForNewBlock(context));
        assertEquals(1, requested(context, base));
        assertEquals(2, requested(context, modifier));
        assertTrue(context.refunded.isEmpty());
        assertEquals(4, source.materials.size());

        context.succeed = false;
        assertFalse(source.getRequiredItemsForNewBlock(context));
        assertTrue(context.refunded.isEmpty());
    }

    @Test
    void existingTranslocatorPlanCreditsOnlyInstalledAttachments() {
        Item base = new Item();
        Item modifier = new Item();
        TranslocatorAnalysisResult source = translocatorWithTwoAttachments(base, modifier);
        RecordingContext context = new RecordingContext();
        TileItemTranslocator tile = new TileItemTranslocator();
        context.tile = tile;

        assertTrue(source.getRequiredItemsForExistingBlock(context));
        assertEquals(2, requested(context, base));
        assertEquals(2, requested(context, modifier));

        tile.attachments[3] = tile.new ItemAttachment(3) {
            @Override
            public Collection<ItemStack> getDrops() {
                return Collections.singletonList(new ItemStack(base));
            }
        };
        assertTrue(source.getRequiredItemsForExistingBlock(context));
        assertEquals(1, requested(context, base));
        assertEquals(2, requested(context, modifier));
        assertTrue(context.refunded.isEmpty());
    }

    private static TranslocatorAnalysisResult translocatorWithTwoAttachments(Item base, Item modifier) {
        TranslocatorAnalysisResult result = new TranslocatorAnalysisResult();
        // Native attachment drops list the base translocator first, followed by its modifiers.
        for (Item item : new Item[] { base, modifier, base, modifier }) {
            PortableItemStack material = new PortableItemStack();
            material.itemStack = new ItemStack(item);
            result.materials.add(material);
        }
        return result;
    }

    private static long requested(RecordingInventory inventory, Item item) {
        return inventory.requested.stream().filter(stack -> stack.item == item).mapToLong(stack -> stack.stackSize).sum();
    }

    @Test
    void replacementReusesExistingComponentsAndCoalescesMissingRequests() {
        RecordingInventory inventory = new RecordingInventory();
        Item item = new Item();
        ItemStack existing = new ItemStack(item, 3);
        ItemStack required = new ItemStack(item, 2);
        assertTrue(inventory.tryReplaceItems(Arrays.asList(required, new ItemStack(item)), Arrays.asList(existing)));
        assertTrue(inventory.requested.isEmpty());
        assertTrue(inventory.refunded.isEmpty());
        assertEquals(3, existing.stackSize);
        assertEquals(2, required.stackSize);

        inventory.tryReplaceItems(
            Arrays.asList(new ItemStack(item), new ItemStack(item)),
            Collections.emptyList()
        );
        assertEquals(1, inventory.requested.size());
        assertEquals(2, inventory.requested.get(0).stackSize);
    }

    @Test
    void replacementDoesNotReuseDifferentNbtOrRefundWhenConsumptionFails() {
        RecordingInventory inventory = new RecordingInventory();
        Item item = new Item();
        ItemStack oldComponent = new ItemStack(item);
        NBTTagCompound oldTag = new NBTTagCompound();
        oldTag.setString("filter", "old");
        oldComponent.setTagCompound(oldTag);
        ItemStack required = new ItemStack(item);
        NBTTagCompound newTag = new NBTTagCompound();
        newTag.setString("filter", "new");
        required.setTagCompound(newTag);

        inventory.succeed = false;
        assertFalse(inventory.tryReplaceItems(Arrays.asList(required), Arrays.asList(oldComponent)));
        assertEquals(1, inventory.requested.size());
        assertTrue(ItemStack.areItemStackTagsEqual(required, inventory.requested.get(0).getItemStack()));
        assertTrue(inventory.refunded.isEmpty());
        assertEquals(1, oldComponent.stackSize);
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
        List<BigItemStack> requested = Collections.emptyList();
        List<BigItemStack> refunded = new ArrayList<>();

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            assertEquals(0, flags);
            requested = items;
            return BooleanObjectImmutablePair.of(succeed, succeed ? items : null);
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

    private static class RecordingContext extends RecordingInventory implements IBlockApplyContext {

        TileEntity tile;

        @Override
        public TileEntity getTileEntity() { return tile; }

        @Override
        public World getWorld() { throw new AssertionError("Material planning must not modify the world"); }

        @Override
        public int getX() { return 0; }

        @Override
        public int getY() { return 0; }

        @Override
        public int getZ() { return 0; }

        @Override
        public EntityPlayer getRealPlayer() { return null; }

        @Override
        public MMConfig getConfig() { return null; }

        @Override
        public boolean tryApplyAction(double complexity) { return true; }

        @Override
        public void warn(IChatComponent message) { throw new AssertionError(message); }

        @Override
        public void error(IChatComponent message) { throw new AssertionError(message); }
    }
}
