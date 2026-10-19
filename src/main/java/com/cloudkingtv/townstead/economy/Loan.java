package com.cloudkingtv.townstead.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * A bank loan: the treasury gets the amount at once and pays it back with interest a little every settlement,
 * like Cities: Skylines' loans. {@code owed} is what's left to pay, {@code daily} the payment per day (cents).
 */
public record Loan(Offer offer, long owed, long daily) {
    /** The three loans a town can take, one of each at a time. */
    public enum Offer implements StringRepresentable {
        SMALL(5_000, 10, 5), MEDIUM(15_000, 20, 8), LARGE(40_000, 40, 12);

        public static final Codec<Offer> CODEC = StringRepresentable.fromEnum(Offer::values);
        /** Amount lent, in cents. */
        public final long amount;
        /** Days to pay it back (Minecraft days). */
        public final int days;
        /** Total interest, percent of the amount. */
        public final int interest;

        Offer(long amount, int days, int interest) {
            this.amount = amount;
            this.days = days;
            this.interest = interest;
        }

        public long total() { return amount * (100 + interest) / 100; }

        public Loan take() { return new Loan(this, total(), (total() + days - 1) / days); }

        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }

        @Override
        public String getSerializedName() { return key(); }
    }

    public static final Codec<Loan> CODEC = RecordCodecBuilder.create(i -> i.group(
            Offer.CODEC.fieldOf("offer").forGetter(Loan::offer),
            Codec.LONG.fieldOf("owed").forGetter(Loan::owed),
            Codec.LONG.fieldOf("daily").forGetter(Loan::daily)
    ).apply(i, Loan::new));

    public static final StreamCodec<ByteBuf, Loan> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> Offer.values()[i], Offer::ordinal), Loan::offer,
            ByteBufCodecs.VAR_LONG, Loan::owed, ByteBufCodecs.VAR_LONG, Loan::daily, Loan::new);

    public Loan pay(long amount) {
        return new Loan(offer, Math.max(0, owed - amount), daily);
    }
}
