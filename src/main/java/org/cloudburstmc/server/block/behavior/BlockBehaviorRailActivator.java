package org.cloudburstmc.server.block.behavior;

import com.nukkitx.math.vector.Vector3i;
import org.cloudburstmc.api.block.Block;
import org.cloudburstmc.api.block.BlockTraits;
import org.cloudburstmc.api.item.ItemStack;
import org.cloudburstmc.api.util.data.RailDirection;
import org.cloudburstmc.server.level.CloudLevel;
import org.cloudburstmc.server.registry.CloudItemRegistry;
import org.cloudburstmc.server.utils.Rail;

import static org.cloudburstmc.api.block.BlockTypes.ACTIVATOR_RAIL;

public class BlockBehaviorRailActivator extends BlockBehaviorRail {

    public BlockBehaviorRailActivator() {
        super(ACTIVATOR_RAIL, BlockTraits.SIMPLE_RAIL_DIRECTION);
        canBePowered = true;
    }

    @Override
    public int onUpdate(Block block, int type) {
        if (type == CloudLevel.BLOCK_UPDATE_NORMAL || type == CloudLevel.BLOCK_UPDATE_REDSTONE || type == CloudLevel.BLOCK_UPDATE_SCHEDULED) {
            if (super.onUpdate(block, type) == CloudLevel.BLOCK_UPDATE_NORMAL) {
                return 0; // Already broken
            }

            var level = (CloudLevel) block.getLevel();
            boolean wasPowered = isActive(block.getState());
            boolean isPowered = level.isBlockPowered(block.getPosition())
                    || checkSurrounding(block, block.getPosition(), true, 0)
                    || checkSurrounding(block, block.getPosition(), false, 0);
            boolean hasUpdate = false;

            if (wasPowered != isPowered) {
                setActive(block, isPowered);
                hasUpdate = true;
            }

            if (hasUpdate) {
                level.updateAround(block.getPosition().down());
                if (getOrientation(block.getState()).isAscending()) {
                    level.updateAround(block.getPosition().up());
                }
            }
            return type;
        }
        return 0;
    }

    /**
     * Check the surrounding of the rail
     *
     * @param pos      The rail position
     * @param relative The relative of the rail that will be checked
     * @param power    The count of the rail that had been counted
     * @return Boolean of the surrounding area. Where the powered rail on!
     */
    protected boolean checkSurrounding(Block block, Vector3i pos, boolean relative, int power) {
        if (power >= 8) {
            return false;
        }

        int dx = pos.getX();
        int dy = pos.getY();
        int dz = pos.getZ();

        BlockBehaviorRail behavior;
        var state = block.getLevel().getBlock(dx, dy, dz).getState();

        if (Rail.isRailBlock(state)) {
            behavior = (BlockBehaviorRail) state.getBehavior();
        } else {
            return false;
        }

        RailDirection base = null;
        boolean onStraight = true;

        switch (behavior.getOrientation(state)) {
            case NORTH_SOUTH:
                if (relative) {
                    dz++;
                } else {
                    dz--;
                }
                break;
            case EAST_WEST:
                if (relative) {
                    dx--;
                } else {
                    dx++;
                }
                break;
            case ASCENDING_EAST:
                if (relative) {
                    dx--;
                } else {
                    dx++;
                    dy++;
                    onStraight = false;
                }
                base = RailDirection.EAST_WEST;
                break;
            case ASCENDING_WEST:
                if (relative) {
                    dx--;
                    dy++;
                    onStraight = false;
                } else {
                    dx++;
                }
                base = RailDirection.EAST_WEST;
                break;
            case ASCENDING_NORTH:
                if (relative) {
                    dz++;
                } else {
                    dz--;
                    dy++;
                    onStraight = false;
                }
                base = RailDirection.NORTH_SOUTH;
                break;
            case ASCENDING_SOUTH:
                if (relative) {
                    dz++;
                    dy++;
                    onStraight = false;
                } else {
                    dz--;
                }
                base = RailDirection.NORTH_SOUTH;
                break;
            default:
                return false;
        }

        return canPowered(block, Vector3i.from(dx, dy, dz), base, power, relative)
                || onStraight && canPowered(block, Vector3i.from(dx, dy - 1, dz), base, power, relative);
    }

    protected boolean canPowered(Block block, Vector3i pos, RailDirection direction, int power, boolean relative) {
        var state = block.getLevel().getBlock(pos).getState();

        if (state.getType() != ACTIVATOR_RAIL) {
            return false;
        }

        RailDirection base = ((BlockBehaviorRailActivator) state.getBehavior()).getOrientation(state);

        return (direction != RailDirection.EAST_WEST
                || base != RailDirection.NORTH_SOUTH
                && base != RailDirection.ASCENDING_NORTH
                && base != RailDirection.ASCENDING_SOUTH)
                && (direction != RailDirection.NORTH_SOUTH
                || base != RailDirection.EAST_WEST
                && base != RailDirection.ASCENDING_EAST
                && base != RailDirection.ASCENDING_WEST)
                && (((CloudLevel) block.getLevel()).isBlockPowered(pos) || checkSurrounding(block, pos, relative, power + 1));
    }

    @Override
    public ItemStack[] getDrops(Block block, ItemStack hand) {
        return new ItemStack[]{
                CloudItemRegistry.get().getItem(ACTIVATOR_RAIL)
        };
    }
}
