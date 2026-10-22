package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

import crazypants.enderio.conduit.IConduit;
import crazypants.enderio.conduit.IConduitBundle;
import crazypants.enderio.conduit.IConduitItem;
import crazypants.enderio.conduit.TileConduitBundle;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public class EnderIOAnalysisResult implements ITileAnalysisIntegration {

    public List<ConduitData> conduits = new ArrayList<>();

    @EqualsAndHashCode
    public static class ConduitData {

        public PortableItemStack item;
        public NBTTagCompound settings;
    }

    public static EnderIOAnalysisResult analyze(TileEntity te) {
        if (!(te instanceof IConduitBundle bundle)) return null;
        EnderIOAnalysisResult result = new EnderIOAnalysisResult();
        for (IConduit conduit : bundle.getConduits()) {
            ConduitData data = new ConduitData();
            data.item = PortableItemStack.withNBT(conduit.createItem());
            data.settings = new NBTTagCompound();
            conduit.writeToNBT(data.settings);
            stripRuntimeState(data.settings);
            result.conduits.add(data);
        }
        return result;
    }

    static void stripRuntimeState(NBTTagCompound tag) {
        for (String key : new String[] {
            "connections", "externalConnections", "signalActive", "tank", "energyStoredRF", "energyStored", "playerID"
        }) {
            tag.removeTag(key);
        }
    }

    private List<IConduit> createConduits(IBlockApplyContext ctx) {
        List<IConduit> result = new ArrayList<>();
        for (ConduitData data : conduits) {
            ItemStack stack = data.item.toStack();
            if (stack == null || !(stack.getItem() instanceof IConduitItem item)) return null;
            IConduit conduit = item.createConduit(stack, ctx.getRealPlayer());
            if (conduit == null) return null;
            NBTTagCompound settings = (NBTTagCompound) data.settings.copy();
            // Keep ownership assigned by the normal item placement path, never copy the source's AE grid owner.
            NBTTagCompound initial = new NBTTagCompound();
            conduit.writeToNBT(initial);
            if (initial.hasKey("playerID")) settings.setTag("playerID", initial.getTag("playerID").copy());
            conduit.readFromNBT(settings, TileConduitBundle.NBT_VERSION);
            result.add(conduit);
        }
        return result;
    }

    private static List<ItemStack> getDrops(List<IConduit> conduits) {
        List<ItemStack> drops = new ArrayList<>();
        for (IConduit conduit : conduits)
            drops.addAll(conduit.getDrops());
        return drops;
    }

    private static List<IConduit> getReplacedConduits(IConduitBundle bundle, List<IConduit> replacements) {
        List<IConduit> existing = new ArrayList<>();
        for (IConduit replacement : replacements) {
            IConduit conduit = bundle.getConduit(replacement.getBaseConduitType());
            if (conduit != null) existing.add(conduit);
        }
        return existing;
    }

    @Override
    public boolean apply(IBlockApplyContext ctx) {
        if (!(ctx.getTileEntity() instanceof IConduitBundle bundle)) return false;
        List<IConduit> replacements = createConduits(ctx);
        if (replacements == null) return false;
        List<IConduit> existing = getReplacedConduits(bundle, replacements);
        if (!ctx.tryReplaceItems(getDrops(replacements), getDrops(existing))) {
            ctx.warn(new ChatComponentTranslation("mm.info.warning.missing_components"));
            return false;
        }
        // Removing a conduit first flushes its network's shared buffers back into individual conduits.
        for (IConduit conduit : existing)
            bundle.removeConduit(conduit);
        // Preserve destination buffers, without ever cloning the source's energy, fluids or gas.
        for (IConduit replacement : replacements) {
            IConduit old = null;
            for (IConduit candidate : existing) {
                if (candidate.getBaseConduitType() == replacement.getBaseConduitType()) old = candidate;
            }
            if (old == null) continue;
            NBTTagCompound previous = new NBTTagCompound();
            old.writeToNBT(previous);
            NBTTagCompound settings = new NBTTagCompound();
            replacement.writeToNBT(settings);
            for (String key : new String[] {
                "tank", "energyStoredRF"
            }) {
                if (previous.hasKey(key)) settings.setTag(key, previous.getTag(key).copy());
            }
            replacement.readFromNBT(settings, TileConduitBundle.NBT_VERSION);
        }
        // ponytail: overlay copied types; exact bundle replacement needs recovery of buffers in removed, unrelated conduits.
        for (IConduit conduit : replacements)
            bundle.addConduit(conduit);
        bundle.dirty();
        bundle.getEntity().markDirty();
        ctx.getWorld().markBlockForUpdate(ctx.getX(), ctx.getY(), ctx.getZ());
        return true;
    }

    @Override
    public boolean getRequiredItemsForExistingBlock(IBlockApplyContext ctx) {
        if (!(ctx.getTileEntity() instanceof IConduitBundle bundle)) return false;
        List<IConduit> replacements = createConduits(ctx);
        return replacements != null && ctx.tryReplaceItems(getDrops(replacements), getDrops(getReplacedConduits(bundle, replacements)));
    }

    @Override
    public boolean getRequiredItemsForNewBlock(IBlockApplyContext ctx) {
        List<IConduit> replacements = createConduits(ctx);
        return replacements != null && ctx.tryReplaceItems(getDrops(replacements), new ArrayList<>());
    }

    @Override
    public void transform(Transform transform) {
        for (ConduitData data : conduits)
            transformSettings(data.settings, transform);
    }

    static void transformSettings(NBTTagCompound tag, Transform transform) {
        NBTTagCompound original = (NBTTagCompound) tag.copy();
        // Ender IO stores per-face settings either in six-element arrays or in keys suffixed with a direction name.
        for (String key : new HashSet<String>(original.func_150296_c())) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                if (key.endsWith("." + side.name())) tag.removeTag(key);
            }
        }
        for (String key : new HashSet<String>(original.func_150296_c())) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                String suffix = "." + side.name();
                if (key.endsWith(suffix)) {
                    tag.setTag(key.substring(0, key.length() - suffix.length()) + "." + transform.apply(side).name(), original.getTag(key).copy());
                }
            }
        }
        for (String key : new String[] {
            "conModes", "forcedConnections", "signalColors", "signalStrengths"
        }) {
            byte[] values = original.getByteArray(key);
            if (values.length != 6) continue;
            byte[] rotated = new byte[6];
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                rotated[transform.apply(side).ordinal()] = values[side.ordinal()];
            tag.setByteArray(key, rotated);
        }
        if (original.hasKey("roundRobin", 3)) {
            tag.setInteger("roundRobin", transform.applyBits(original.getInteger("roundRobin")) & 0xFF);
        }
    }

    @Override
    public EnderIOAnalysisResult clone() {
        EnderIOAnalysisResult copy = new EnderIOAnalysisResult();
        for (ConduitData data : conduits) {
            ConduitData entry = new ConduitData();
            entry.item = data.item.clone();
            entry.settings = (NBTTagCompound) data.settings.copy();
            copy.conduits.add(entry);
        }
        return copy;
    }

    @Override
    public void getItemTag(ItemStack stack) {}

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {}

    @Override
    public void migrate() {}
}
