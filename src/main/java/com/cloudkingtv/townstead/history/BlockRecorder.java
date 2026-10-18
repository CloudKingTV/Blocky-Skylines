package com.cloudkingtv.townstead.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every block an edit changes goes through here, so the edit can be undone exactly: the first state seen at a
 * position is its "before", the last one written its "after".
 */
public final class BlockRecorder {
    public record Change(BlockPos pos, BlockState before, BlockState after) {}

    private final ServerLevel level;
    private final Map<BlockPos, BlockState[]> changes = new LinkedHashMap<>();

    public BlockRecorder(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return level;
    }

    public BlockState get(BlockPos pos) {
        return level.getBlockState(pos);
    }

    public void set(BlockPos pos, BlockState state, int flags) {
        BlockPos key = pos.immutable();
        BlockState before = level.getBlockState(key);
        if (before.equals(state)) return;
        changes.computeIfAbsent(key, k -> new BlockState[] {before, null});
        level.setBlock(key, state, flags);
        changes.get(key)[1] = level.getBlockState(key);
    }

    /** Positions whose final state differs from where they started, in the order they were first touched. */
    public List<Change> changes() {
        List<Change> out = new ArrayList<>();
        changes.forEach((pos, s) -> {
            if (!s[0].equals(s[1])) out.add(new Change(pos, s[0], s[1]));
        });
        return out;
    }
}
