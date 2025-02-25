package org.cloudburstmc.server.item.behavior;

import com.nukkitx.math.vector.Vector3f;
import lombok.RequiredArgsConstructor;
import org.cloudburstmc.api.block.Block;
import org.cloudburstmc.api.block.BlockTraits;
import org.cloudburstmc.api.entity.EntityType;
import org.cloudburstmc.api.entity.EntityTypes;
import org.cloudburstmc.api.entity.vehicle.Minecart;
import org.cloudburstmc.api.item.ItemStack;
import org.cloudburstmc.api.level.Level;
import org.cloudburstmc.api.level.Location;
import org.cloudburstmc.api.player.Player;
import org.cloudburstmc.api.util.Direction;
import org.cloudburstmc.api.util.data.RailDirection;
import org.cloudburstmc.server.registry.EntityRegistry;
import org.cloudburstmc.server.utils.Rail;

/**
 * author: MagicDroidX
 * Nukkit Project
 */
@RequiredArgsConstructor
public class ItemMinecartBehavior extends CloudItemBehavior {

    protected final EntityType<? extends Minecart> minecartType;

    @Override
    public boolean canBeActivated() {
        return true;
    }

    @Override
    public ItemStack onActivate(ItemStack itemStack, Player player, Block block, Block target, Direction face, Vector3f clickPos, Level level) {
        if (Rail.isRailBlock(target.getState())) {
            RailDirection type = target.getState().ensureTrait(BlockTraits.RAIL_DIRECTION);
            double adjacent = 0.0D;
            if (type.isAscending()) {
                adjacent = 0.5D;
            }
            Vector3f pos = target.getPosition().toFloat().add(0.5, 0.0625 + adjacent, 0.5);
            Minecart minecart = EntityRegistry.get().newEntity(EntityTypes.MINECART, Location.from(pos, level));
            minecart.spawnToAll();

            if (player.isSurvival() || player.isAdventure()) {
                return itemStack.decrementAmount();
            }
        }
        return null;
    }

    @Override
    public int getMaxStackSize(ItemStack item) {
        return 1;
    }
}
