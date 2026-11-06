package com.tvpower.irbrute;

import java.util.ArrayList;
import java.util.List;

/**
 * Кодирует ИК-команды популярных протоколов в формат ConsumerIrManager:
 * несущая частота + массив длительностей (мкс), чередование импульс/пауза,
 * начиная с импульса.
 *
 * Описания протоколов соответствуют нотации IRP (как в IrScrutinizer / DecodeIR).
 */
public final class IrEncoder {

    /** Готовый сигнал. */
    public static final class Signal {
        public final int frequency;
        public final int[] pattern;

        Signal(int frequency, int[] pattern) {
            this.frequency = frequency;
            this.pattern = pattern;
        }

        public long durationMicros() {
            long t = 0;
            for (int p : pattern) t += p;
            return t;
        }
    }

    private static int rc5Toggle = 0;
    private static int rc6Toggle = 0;

    private IrEncoder() {
    }

    public static boolean isSupported(String protocol) {
        try {
            encode(protocol, 0, 0, 0);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Signal encode(String protocol, int d, int s, int f) {
        switch (protocol) {
            case "NEC1":
                return nec(d, s, f, 16);
            case "NECX":
                return nec(d, s, f, 8);
            case "RC5":
                return rc5(d, f);
            case "RC6":
                return rc6(d, f);
            case "SONY12":
                return sony(d, -1, f, 5);
            case "SONY15":
                return sony(d, -1, f, 8);
            case "SONY20":
                return sony(d, s, f, 5);
            case "JVC":
                return jvc(d, f);
            case "KASEIKYO":
                return kaseikyo(d, s, f);
            case "PANASONIC_OLD":
                return panasonicOld(d, f);
            case "MITSUBISHI":
                return mitsubishi(d, f);
            case "RCA":
                return rca(d, f, 58000);
            case "RCA38":
                return rca(d, f, 38700);
            case "SHARP":
                return sharp(d, f);
            case "PROTON":
                return proton(d, f);
            case "AIWA":
                return aiwa(d, s, f);
            case "PIONEER":
                return pioneer(d, s, f);
            case "EMERSON":
                return emerson(d, f);
            default:
                throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Протоколы
    // ---------------------------------------------------------------------------------------

    /** NEC1 {38.4k,564}<1,-1|1,-3>(16,-8,D:8,S:8,F:8,~F:8,1,^108m,(16,-4,1,^108m)*); NECx: 8,-8. */
    private static Signal nec(int d, int s, int f, int header) {
        final int u = 564;
        B b = new B(38400, u, false);
        b.frameStart();
        b.mark(header).space(header == 16 ? 8 : 8);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        b.bits(d, 8, zero, one).bits(s, 8, zero, one).bits(f, 8, zero, one).bits(~f, 8, zero, one);
        b.mark(1).extent(108000);
        if (header == 16) {
            // Один код повтора (как при коротком нажатии) — не «двойное» нажатие
            b.frameStart();
            b.mark(16).space(4).mark(1).extent(108000);
        }
        return b.build();
    }

    /** RC5 {36k,msb,889}<1,-1|-1,1>(1,~F:1:6,T:1,D:5,F:6,^114m). */
    private static Signal rc5(int d, int f) {
        B b = new B(36000, 889, true);
        int[] zero = {1, -1};
        int[] one = {-1, 1};
        int t = rc5Toggle;
        rc5Toggle ^= 1;
        b.frameStart();
        b.bits(1, 1, zero, one);
        b.bits(~(f >> 6), 1, zero, one);
        b.bits(t, 1, zero, one);
        b.bits(d, 5, zero, one);
        b.bits(f, 6, zero, one);
        b.extent(114000);
        return b.build();
    }

    /** RC6-0-16 {36k,444,msb}<-1,1|1,-1>(6,-2,1:1,0:3,<-2,2|2,-2>(T:1),D:8,F:8,^107m). */
    private static Signal rc6(int d, int f) {
        B b = new B(36000, 444, true);
        int[] zero = {-1, 1};
        int[] one = {1, -1};
        int t = rc6Toggle;
        rc6Toggle ^= 1;
        b.frameStart();
        b.mark(6).space(2);
        b.bits(1, 1, zero, one);
        b.bits(0, 3, zero, one);
        b.bits(t, 1, new int[]{-2, 2}, new int[]{2, -2});
        b.bits(d, 8, zero, one);
        b.bits(f, 8, zero, one);
        b.extent(107000);
        return b.build();
    }

    /** Sony {40k,600}<1,-1|2,-1>(4,-1,F:7,D:5|8,[S:8],^45m) — отправляется 3 раза. */
    private static Signal sony(int d, int s, int f, int dBits) {
        B b = new B(40000, 600, false);
        int[] zero = {1, -1};
        int[] one = {2, -1};
        for (int i = 0; i < 3; i++) {
            b.frameStart();
            b.mark(4).space(1);
            b.bits(f, 7, zero, one);
            b.bits(d, dBits, zero, one);
            if (s >= 0) b.bits(s, 8, zero, one);
            b.extent(45000);
        }
        return b.build();
    }

    /** JVC {38k,525}<1,-1|1,-3>(16,-8,(D:8,F:8,1,-45)+). */
    private static Signal jvc(int d, int f) {
        B b = new B(38000, 525, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        b.mark(16).space(8);
        for (int i = 0; i < 2; i++) {
            b.bits(d, 8, zero, one).bits(f, 8, zero, one);
            b.mark(1).space(45);
        }
        return b.build();
    }

    /** Panasonic (Kaseikyo) {37k,432}<1,-1|1,-3>(8,-4,2:8,32:8,D:8,S:8,F:8,(D^S^F):8,1,-173). */
    private static Signal kaseikyo(int d, int s, int f) {
        B b = new B(37000, 432, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        b.mark(8).space(4);
        b.bits(2, 8, zero, one).bits(32, 8, zero, one);
        b.bits(d, 8, zero, one).bits(s, 8, zero, one).bits(f, 8, zero, one);
        b.bits(d ^ s ^ f, 8, zero, one);
        b.mark(1).space(173);
        return b.build();
    }

    /** Panasonic_Old {57.6k,833}<1,-1|1,-3>(4,-4,D:5,F:6,~D:5,~F:6,1,-44m). */
    private static Signal panasonicOld(int d, int f) {
        B b = new B(57600, 833, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        for (int i = 0; i < 2; i++) {
            b.mark(4).space(4);
            b.bits(d, 5, zero, one).bits(f, 6, zero, one).bits(~d, 5, zero, one).bits(~f, 6, zero, one);
            b.mark(1).spaceUs(44000);
        }
        return b.build();
    }

    /** Mitsubishi {32.6k,300}<1,-3|1,-7>(D:8,F:8,1,-80) — 2 раза. */
    private static Signal mitsubishi(int d, int f) {
        B b = new B(32600, 300, false);
        int[] zero = {1, -3};
        int[] one = {1, -7};
        for (int i = 0; i < 2; i++) {
            b.bits(d, 8, zero, one).bits(f, 8, zero, one);
            b.mark(1).space(80);
        }
        return b.build();
    }

    /** RCA {58k,460,msb}<1,-2|1,-4>(8,-8,D:4,F:8,~D:4,~F:8,1,-16) — 2 раза. */
    private static Signal rca(int d, int f, int freq) {
        B b = new B(freq, 460, true);
        int[] zero = {1, -2};
        int[] one = {1, -4};
        for (int i = 0; i < 2; i++) {
            b.mark(8).space(8);
            b.bits(d, 4, zero, one).bits(f, 8, zero, one).bits(~d, 4, zero, one).bits(~f, 8, zero, one);
            b.mark(1).space(16);
        }
        return b.build();
    }

    /** Sharp {38k,264}<1,-3|1,-7>(D:5,F:8,1:2,1,-165,D:5,~F:8,2:2,1,-165). */
    private static Signal sharp(int d, int f) {
        B b = new B(38000, 264, false);
        int[] zero = {1, -3};
        int[] one = {1, -7};
        b.bits(d, 5, zero, one).bits(f, 8, zero, one).bits(1, 2, zero, one);
        b.mark(1).space(165);
        b.bits(d, 5, zero, one).bits(~f, 8, zero, one).bits(2, 2, zero, one);
        b.mark(1).space(165);
        return b.build();
    }

    /** Proton {38k,500}<1,-1|1,-3>(16,-8,D:8,1,-8,F:8,1,^63m). */
    private static Signal proton(int d, int f) {
        B b = new B(38000, 500, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        b.frameStart();
        b.mark(16).space(8);
        b.bits(d, 8, zero, one);
        b.mark(1).space(8);
        b.bits(f, 8, zero, one);
        b.mark(1).extent(63000);
        return b.build();
    }

    /** Aiwa {38k,550}<1,-1|1,-3>(16,-8,D:8,S:5,~D:8,~S:5,F:8,~F:8,1,-42,(16,-8,1,-165)*). */
    private static Signal aiwa(int d, int s, int f) {
        B b = new B(38000, 550, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        b.mark(16).space(8);
        b.bits(d, 8, zero, one).bits(s, 5, zero, one).bits(~d, 8, zero, one).bits(~s, 5, zero, one);
        b.bits(f, 8, zero, one).bits(~f, 8, zero, one);
        b.mark(1).space(42);
        b.mark(16).space(8).mark(1).space(165);
        return b.build();
    }

    /** Pioneer {40k,564}<1,-1|1,-3>(16,-8,D:8,S:8,F:8,~F:8,1,-25m) — 2 раза. */
    private static Signal pioneer(int d, int s, int f) {
        B b = new B(40000, 564, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        for (int i = 0; i < 2; i++) {
            b.mark(16).space(8);
            b.bits(d, 8, zero, one).bits(s, 8, zero, one).bits(f, 8, zero, one).bits(~f, 8, zero, one);
            b.mark(1).spaceUs(25000);
        }
        return b.build();
    }

    /** Emerson {36.7k,872}<1,-1|1,-3>(4,-4,D:6,F:6,~D:6,~F:6,1,-39) — 2 раза. */
    private static Signal emerson(int d, int f) {
        B b = new B(36700, 872, false);
        int[] zero = {1, -1};
        int[] one = {1, -3};
        for (int i = 0; i < 2; i++) {
            b.mark(4).space(4);
            b.bits(d, 6, zero, one).bits(f, 6, zero, one).bits(~d, 6, zero, one).bits(~f, 6, zero, one);
            b.mark(1).space(39);
        }
        return b.build();
    }

    // ---------------------------------------------------------------------------------------
    // Построитель сигнала
    // ---------------------------------------------------------------------------------------

    /** Собирает длительности: положительные — импульс, отрицательные — пауза. */
    private static final class B {
        final int freq;
        final int unit;
        final boolean msb;
        final List<Integer> d = new ArrayList<>();
        long elapsed;
        long frameStart;

        B(int freq, int unit, boolean msb) {
            this.freq = freq;
            this.unit = unit;
            this.msb = msb;
        }

        void frameStart() {
            frameStart = elapsed;
        }

        B mark(int units) {
            add(units * unit);
            return this;
        }

        B space(int units) {
            add(-units * unit);
            return this;
        }

        B spaceUs(int us) {
            add(-us);
            return this;
        }

        /** Пауза до достижения полной длины кадра (^ в IRP). */
        B extent(int totalUs) {
            long rest = totalUs - (elapsed - frameStart);
            if (rest < unit) rest = unit;
            add((int) -rest);
            return this;
        }

        B bits(int value, int count, int[] zero, int[] one) {
            for (int i = 0; i < count; i++) {
                int bitIndex = msb ? (count - 1 - i) : i;
                int bit = (value >> bitIndex) & 1;
                int[] sym = bit == 1 ? one : zero;
                for (int x : sym) add(x * unit);
            }
            return this;
        }

        void add(int signedUs) {
            if (signedUs == 0) return;
            // Сигнал обязан начинаться с импульса — ведущую паузу отбрасываем
            if (d.isEmpty() && signedUs < 0) {
                elapsed += -signedUs;
                return;
            }
            elapsed += Math.abs(signedUs);
            if (!d.isEmpty()) {
                int last = d.get(d.size() - 1);
                if ((last > 0) == (signedUs > 0)) {
                    d.set(d.size() - 1, last + signedUs);
                    return;
                }
            }
            d.add(signedUs);
        }

        Signal build() {
            int[] out = new int[d.size()];
            for (int i = 0; i < out.length; i++) out[i] = Math.abs(d.get(i));
            return new Signal(freq, out);
        }
    }
}
