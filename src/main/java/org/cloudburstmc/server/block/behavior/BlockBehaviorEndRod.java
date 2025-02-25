package org.cloudburstmc.server.block.behavior;

import com.nukkitx.math.vector.Vector3f;
import org.cloudburstmc.api.block.Block;
import org.cloudburstmc.api.block.BlockTraits;
import org.cloudburstmc.api.item.ItemStack;
import org.cloudburstmc.api.player.Player;
import org.cloudburstmc.api.util.Direction;
import org.cloudburstmc.server.registry.CloudItemRegistry;

public class BlockBehaviorEndRod extends BlockBehaviorTransparent {


    @Override
    public boolean canBePushed() {
        return true;
    }


//    @Override
//    public float getMinX() {
//        return this.getX() + 0.4f;
//    }
//
//    @Override
//    public float getMinZ() {
//        return this.getZ() + 0.4f;
//    }
//
//    @Override
//    public float getMaxX() {
//        return this.getX() + 0.6f;
//    }
//
//    @Override
//    public float getMaxZ() {
//        return this.getZ() + 0.6f;
//    }

    @Override
    public boolean place(ItemStack item, Block block, Block target, Direction face, Vector3f clickPos, Player player) {
        placeBlock(block, item.getBehavior().getBlock(item).withTrait(BlockTraits.FACING_DIRECTION, face));
        return true;
    }

    @Override
    public ItemStack toItem(Block block) {
        return CloudItemRegistry.get().getItem(block.getState().getType().getDefaultState());
    }


}
