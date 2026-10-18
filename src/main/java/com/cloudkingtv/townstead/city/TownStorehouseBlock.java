package com.cloudkingtv.townstead.city;

import com.cloudkingtv.townstead.network.StorehousePayloads;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Town Storehouse: the town's material stock, used by everything the planner builds. Use it with a building
 * material in hand to deposit that stack, sneak-use to deposit everything, or use it empty-handed to browse and
 * withdraw. The stock lives with the town, so every storehouse of a town shares it.
 */
public class TownStorehouseBlock extends HorizontalDirectionalBlock {
    public TownStorehouseBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (!(level instanceof ServerLevel server) || !(by instanceof ServerPlayer player)) return;
        CityManager cities = CityManager.get(server.getServer());
        Optional<City> city = cities.at(server, pos);
        if (city.isEmpty() || !city.get().owner().equals(player.getUUID())) {
            server.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) player.getInventory().add(stack.copyWithCount(1));
            player.sendSystemMessage(Component.translatable("message.townstead.storehouse.needs_town"));
            return;
        }
        cities.addStorehouse(server.getServer(), city.get(), pos);
        player.sendSystemMessage(Component.translatable("message.townstead.storehouse.placed", city.get().name()));
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        CityManager.get(level.getServer()).removeStorehouse(level.getServer(), pos);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (!StorehouseService.accepts(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (player instanceof ServerPlayer sp) {
            StorehouseService.access(sp, pos).ifPresent(city -> {
                if (sp.isShiftKeyDown()) StorehouseService.depositAll(sp, city);
                else StorehouseService.deposit(sp, city, stack);
            });
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            StorehouseService.access(sp, pos).ifPresent(city -> {
                if (sp.isShiftKeyDown()) StorehouseService.depositAll(sp, city);
                else PacketDistributor.sendToPlayer(sp, new StorehousePayloads.Open(city.id(), pos));
            });
        }
        return InteractionResult.SUCCESS;
    }
}
