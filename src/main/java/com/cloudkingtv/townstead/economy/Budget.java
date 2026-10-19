package com.cloudkingtv.townstead.economy;

import com.cloudkingtv.townstead.service.ServiceType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * A town's money settings and records: tax rates per zone kind, a budget per department, loans, and a short ledger
 * of recent settlements. Money is counted in hundredths of an emerald ("cents") so small daily amounts add up
 * exactly; the treasury itself lives on the City.
 */
public final class Budget {
    /** Tax kinds, in bar order: homes, shops, workshops, offices. */
    public static final int HOMES = 0, SHOPS = 1, WORKSHOPS = 2, OFFICES = 3, TAX_KINDS = 4;
    public static final int MIN_TAX = 0, MAX_TAX = 29, DEFAULT_TAX = 9;
    public static final int MIN_BUDGET = 50, MAX_BUDGET = 150, DEFAULT_BUDGET = 100;
    public static final int LEDGER_SIZE = 24;

    /** What one settlement did: money in, money out, and the treasury after. */
    public record Entry(long income, long expenses, long treasury) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("in").forGetter(Entry::income),
                Codec.LONG.fieldOf("out").forGetter(Entry::expenses),
                Codec.LONG.fieldOf("treasury").forGetter(Entry::treasury)
        ).apply(i, Entry::new));
        static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Entry::income, ByteBufCodecs.VAR_LONG, Entry::expenses, ByteBufCodecs.VAR_LONG, Entry::treasury, Entry::new);
    }

    public final int[] taxes = new int[TAX_KINDS];
    public final int[] budgets = new int[ServiceType.Department.values().length];
    public final List<Loan> loans = new ArrayList<>();
    public final List<Entry> ledger = new ArrayList<>();

    public Budget() {
        java.util.Arrays.fill(taxes, DEFAULT_TAX);
        java.util.Arrays.fill(budgets, DEFAULT_BUDGET);
    }

    private Budget(List<Integer> taxes, List<Integer> budgets, List<Loan> loans, List<Entry> ledger) {
        this();
        for (int i = 0; i < Math.min(taxes.size(), TAX_KINDS); i++) this.taxes[i] = Mth.clamp(taxes.get(i), MIN_TAX, MAX_TAX);
        for (int i = 0; i < Math.min(budgets.size(), this.budgets.length); i++) this.budgets[i] = Mth.clamp(budgets.get(i), MIN_BUDGET, MAX_BUDGET);
        this.loans.addAll(loans);
        this.ledger.addAll(ledger);
    }

    public static final Codec<Budget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.listOf().optionalFieldOf("taxes", List.of()).forGetter(b -> ints(b.taxes)),
            Codec.INT.listOf().optionalFieldOf("budgets", List.of()).forGetter(b -> ints(b.budgets)),
            Loan.CODEC.listOf().optionalFieldOf("loans", List.of()).forGetter(b -> b.loans),
            Entry.CODEC.listOf().optionalFieldOf("ledger", List.of()).forGetter(b -> b.ledger)
    ).apply(i, Budget::new));

    public static final StreamCodec<ByteBuf, Budget> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), b -> ints(b.taxes),
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), b -> ints(b.budgets),
            Loan.STREAM_CODEC.apply(ByteBufCodecs.list()), b -> b.loans,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), b -> b.ledger,
            Budget::new);

    private static List<Integer> ints(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int v : a) out.add(v);
        return out;
    }

    public Budget copy() {
        return new Budget(ints(taxes), ints(budgets), loans, ledger);
    }

    public int tax(int kind) { return taxes[kind]; }

    public int budget(ServiceType.Department d) { return budgets[d.ordinal()]; }

    public boolean hasLoan(Loan.Offer offer) {
        return loans.stream().anyMatch(l -> l.offer() == offer);
    }

    public void record(Entry entry) {
        ledger.add(entry);
        while (ledger.size() > LEDGER_SIZE) ledger.removeFirst();
    }

    /** "12.3" for 1230 cents: emeralds with one decimal, the way the planner shows money. */
    public static String format(long cents) {
        long tenths = Math.round(cents / 10.0);
        String sign = tenths < 0 ? "-" : "";
        tenths = Math.abs(tenths);
        return sign + (tenths / 10) + "." + (tenths % 10);
    }

    /** Signed: "+12.3" or "-4.0". */
    public static String signed(long cents) {
        return (cents >= 0 ? "+" : "") + format(cents);
    }
}
