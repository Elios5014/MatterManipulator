package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

import codechicken.lib.inventory.InventoryUtils;
import codechicken.translocator.TileItemTranslocator;
import codechicken.translocator.TileTranslocator.Attachment;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public class TranslocatorAnalysisResult implements ITileAnalysisIntegration {

    public NBTTagCompound data;
    public List<PortableItemStack> materials = new ArrayList<>();

    public static TranslocatorAnalysisResult analyze(TileEntity te) {
        if (!(te instanceof TileItemTranslocator tile)) return null;
        TranslocatorAnalysisResult result = new TranslocatorAnalysisResult();
        result.data = new NBTTagCompound();
        for (int side = 0; side < 6; side++) {
            if (tile.attachments[side] == null) continue;
            NBTTagCompound tag = tile.attachments[side].write(new NBTTagCompound());
            tag.removeTag("powering");
            result.data.setTag("atmt" + side, tag);
        }
        for (ItemStack stack : getDrops(tile))
            result.materials.add(PortableItemStack.withNBT(stack));
        return result;
    }

    private TileItemTranslocator createTile(IBlockApplyContext ctx) {
        TileItemTranslocator tile = new TileItemTranslocator();
        tile.setWorldObj(ctx.getWorld());
        tile.xCoord = ctx.getX();
        tile.yCoord = ctx.getY();
        tile.zCoord = ctx.getZ();
        for (int side = 0; side < 6; side++) {
            if (!data.hasKey("atmt" + side)) continue;
            tile.createAttachment(side);
            tile.attachments[side].read(data.getCompoundTag("atmt" + side));
        }
        return tile;
    }

    private static List<ItemStack> getDrops(TileItemTranslocator tile) {
        List<ItemStack> drops = new ArrayList<>();
        for (Attachment attachment : tile.attachments) {
            if (attachment != null) drops.addAll(attachment.getDrops());
        }
        return drops;
    }

    private List<ItemStack> getRequiredItems() {
        List<ItemStack> items = new ArrayList<>();
        for (PortableItemStack material : materials)
            items.add(material.toStack());
        return items;
    }

    @Override
    public boolean apply(IBlockApplyContext ctx) {
        if (!(ctx.getTileEntity() instanceof TileItemTranslocator existing)) return false;
        TileItemTranslocator replacement = createTile(ctx);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (
                replacement.attachments[side.ordinal()] != null && (!ctx.getWorld()
                    .blockExists(
                        ctx.getX() + side.offsetX,
                        ctx.getY() + side.offsetY,
                        ctx.getZ() + side.offsetZ
                    ) ||
                    InventoryUtils.getInventory(
                        ctx.getWorld(),
                        ctx.getX() + side.offsetX,
                        ctx.getY() + side.offsetY,
                        ctx.getZ() + side.offsetZ
                    ) == null)
            ) {
                ctx.warn(new ChatComponentTranslation("mm.info.warning.translocator_support"));
                return false;
            }
        }
        if (!ctx.tryReplaceItems(getRequiredItems(), getDrops(existing))) {
            ctx.warn(new ChatComponentTranslation("mm.info.warning.missing_components"));
            return false;
        }
        ctx.getWorld().setTileEntity(ctx.getX(), ctx.getY(), ctx.getZ(), replacement);
        replacement.markDirty();
        ctx.getWorld().markBlockForUpdate(ctx.getX(), ctx.getY(), ctx.getZ());
        return true;
    }

    @Override
    public boolean getRequiredItemsForExistingBlock(IBlockApplyContext ctx) {
        return ctx.getTileEntity() instanceof TileItemTranslocator existing &&
            ctx.tryReplaceItems(getRequiredItems(), getDrops(existing));
    }

    @Override
    public boolean getRequiredItemsForNewBlock(IBlockApplyContext ctx) {
        return ctx.tryReplaceItems(getRequiredItems(), new ArrayList<>());
    }

    @Override
    public void transform(Transform transform) {
        NBTTagCompound transformed = new NBTTagCompound();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            String key = "atmt" + side.ordinal();
            if (data.hasKey(key)) transformed.setTag("atmt" + transform.apply(side).ordinal(), data.getTag(key).copy());
        }
        data = transformed;
    }

    @Override
    public TranslocatorAnalysisResult clone() {
        TranslocatorAnalysisResult copy = new TranslocatorAnalysisResult();
        copy.data = (NBTTagCompound) data.copy();
        for (PortableItemStack material : materials)
            copy.materials.add(material.clone());
        return copy;
    }

    @Override
    public void getItemTag(ItemStack stack) {}

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {}

    @Override
    public void migrate() {}
}
