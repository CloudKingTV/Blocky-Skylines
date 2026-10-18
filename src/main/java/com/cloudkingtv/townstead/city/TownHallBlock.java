package com.cloudkingtv.townstead.city;

import com.cloudkingtv.townstead.Townstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jspecify.annotations.Nullable;

/** Placing a Town Hall founds a town and claims the land around it. Breaking it disbands the town. */
public class TownHallBlock extends HorizontalDirectionalBlock {
    public TownHallBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
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
        switch (cities.canFound(server, pos, player.getUUID())) {
            case ALREADY_MAYOR -> refuse(server, pos, player, stack, "message.townstead.found.already_mayor");
            case TOO_CLOSE -> refuse(server, pos, player, stack, "message.townstead.found.too_close");
            case OK -> {
                City city = cities.found(server, pos, player);
                server.playSound(null, pos, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0f, 1.0f);
                server.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.2f);
                server.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                        30, 0.6, 0.4, 0.6, 0.02);
                player.sendSystemMessage(Component.translatable("message.townstead.found.success", city.name()));
                player.sendSystemMessage(Component.translatable("message.townstead.found.hint"));
            }
        }
    }

    private static void refuse(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack stack, String key) {
        level.removeBlock(pos, false);
        if (!player.getAbilities().instabuild) player.getInventory().add(stack.copyWithCount(1));
        player.sendSystemMessage(Component.translatable(key));
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) {
            CityManager cities = CityManager.get(server.getServer());
            cities.byTownHall(server, pos).ifPresent(city -> {
                cities.disband(server.getServer(), city);
                Townstead.LOGGER.info("Town '{}' disbanded (Town Hall broken by {})", city.name(), player.getGameProfile().name());
                player.sendSystemMessage(Component.translatable("message.townstead.disbanded", city.name()));
            });
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
