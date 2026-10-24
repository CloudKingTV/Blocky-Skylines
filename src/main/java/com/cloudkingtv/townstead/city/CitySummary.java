package com.cloudkingtv.townstead.city;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Client-visible snapshot of a city (treasury in hundredths of an emerald; storehouses is how many Town Storehouses it has). */
public record CitySummary(UUID id, String name, UUID owner, String ownerName, ResourceKey<Level> dimension,
                          BlockPos townHall, List<TilePos> tiles, long treasury, Map<Identifier, Integer> stock,
                          com.cloudkingtv.townstead.economy.Budget budget, int milestone,
                          List<com.cloudkingtv.townstead.transit.CoachLine> lines,
                          List<com.cloudkingtv.townstead.district.District> districts,
                          java.util.Set<com.cloudkingtv.townstead.district.Policy> policies, int storehouses, List<String> coMayors) {
    public static final StreamCodec<ByteBuf, CitySummary> STREAM_CODEC = new StreamCodec<>() {
        private final StreamCodec<ByteBuf, List<TilePos>> tilesCodec = ByteBufCodecs.collection(ArrayList::new, TilePos.STREAM_CODEC);
        private final StreamCodec<ByteBuf, ResourceKey<Level>> dimCodec = ResourceKey.streamCodec(Registries.DIMENSION);
        private final StreamCodec<ByteBuf, List<com.cloudkingtv.townstead.transit.CoachLine>> linesCodec =
                ByteBufCodecs.collection(ArrayList::new, com.cloudkingtv.townstead.transit.CoachLine.STREAM_CODEC);
        private final StreamCodec<ByteBuf, List<com.cloudkingtv.townstead.district.District>> districtsCodec =
                ByteBufCodecs.collection(ArrayList::new, com.cloudkingtv.townstead.district.District.STREAM_CODEC);
        private final StreamCodec<ByteBuf, java.util.Set<com.cloudkingtv.townstead.district.Policy>> policiesCodec =
                ByteBufCodecs.VAR_INT.<com.cloudkingtv.townstead.district.Policy>map(o -> com.cloudkingtv.townstead.district.Policy.values()[o], Enum::ordinal)
                        .apply(ByteBufCodecs.collection(java.util.HashSet::new));
        private final StreamCodec<ByteBuf, List<String>> namesCodec = ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.collection(ArrayList::new, 64));
        private final StreamCodec<ByteBuf, Map<Identifier, Integer>> stockCodec =
                ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.VAR_INT);

        @Override
        public CitySummary decode(ByteBuf buf) {
            return new CitySummary(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                    UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), dimCodec.decode(buf),
                    BlockPos.STREAM_CODEC.decode(buf), tilesCodec.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), stockCodec.decode(buf),
                    com.cloudkingtv.townstead.economy.Budget.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), linesCodec.decode(buf),
                    districtsCodec.decode(buf), policiesCodec.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), namesCodec.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, CitySummary c) {
            UUIDUtil.STREAM_CODEC.encode(buf, c.id());
            ByteBufCodecs.STRING_UTF8.encode(buf, c.name());
            UUIDUtil.STREAM_CODEC.encode(buf, c.owner());
            ByteBufCodecs.STRING_UTF8.encode(buf, c.ownerName());
            dimCodec.encode(buf, c.dimension());
            BlockPos.STREAM_CODEC.encode(buf, c.townHall());
            tilesCodec.encode(buf, c.tiles());
            ByteBufCodecs.VAR_LONG.encode(buf, c.treasury());
            stockCodec.encode(buf, c.stock());
            com.cloudkingtv.townstead.economy.Budget.STREAM_CODEC.encode(buf, c.budget());
            ByteBufCodecs.VAR_INT.encode(buf, c.milestone());
            linesCodec.encode(buf, c.lines());
            districtsCodec.encode(buf, c.districts());
            policiesCodec.encode(buf, c.policies());
            ByteBufCodecs.VAR_INT.encode(buf, c.storehouses());
            namesCodec.encode(buf, c.coMayors());
        }
    };

    /** Lines, districts and policies as the town's model reads them. */
    public com.cloudkingtv.townstead.district.Civics civics() {
        return new com.cloudkingtv.townstead.district.Civics(lines, districts, policies);
    }

    public boolean contains(int blockX, int blockZ) {
        return tiles.contains(TilePos.ofBlock(blockX, blockZ));
    }
}
