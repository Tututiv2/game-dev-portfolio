package com.auraqol.shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Frames to messages and back, both directions (PROTOCOL.md). Pure.
 *
 * <p>Decoding follows the protocol's forward-compatibility rules exactly:
 * <ul>
 *   <li>unknown {@code type} - {@link Msg.Unknown}, the caller ignores it;</li>
 *   <li>a known type at a higher {@code version}, or with trailing bytes -
 *       decode the fields this file knows and ignore the rest (future versions
 *       only ever APPEND);</li>
 *   <li>truncated, a bad bool, an over-limit length/count -
 *       {@link Wire.DecodeException}: drop the frame.</li>
 * </ul>
 * Encoding always writes version 1, the only version this file knows.
 *
 * <p>Both directions are here because the client SENDS {@code HELLO}/{@code
 * REQUEST} and the tests must round-trip every golden fixture, server-side ones
 * included: if this encoder and the server's ever disagree about one byte, a
 * fixture fails before a player sees it.
 */
public final class Codec {

    public static final int VERSION = 1;

    private Codec() {
    }

    // ------------------------------------------------------------ decode

    /** A server -> client frame ({@code shadowprison:live}). */
    public static Msg decodeS2C(byte[] frame) throws Wire.DecodeException {
        if (frame == null) {
            throw new Wire.DecodeException("no frame");
        }
        Wire.Reader r = new Wire.Reader(frame);
        int type = r.u32Int();
        long version = r.u32();
        return switch (type) {
            case Msg.WELCOME -> welcome(r);
            case Msg.SNAPSHOT -> snapshot(r);
            case Msg.BREAKS, Msg.INCOME, Msg.BALANCE, Msg.PROGRESS, Msg.BOOSTS, Msg.SKILL, Msg.CONTRACT, Msg.AREA ->
                    state(type, r);
            case Msg.RESPONSE -> response(r);
            case Msg.COMBAT -> {
                long kills = r.u32();
                long value = r.s64();
                int n = r.count();
                List<Msg.MobCount> per = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    per.add(new Msg.MobCount(r.str(), r.u32()));
                }
                yield new Msg.Combat(kills, value, List.copyOf(per));
            }
            default -> new Msg.Unknown(type, version);
        };
    }

    /** A client -> server frame ({@code shadowprison:live_req}). */
    public static Msg decodeC2S(byte[] frame) throws Wire.DecodeException {
        if (frame == null) {
            throw new Wire.DecodeException("no frame");
        }
        Wire.Reader r = new Wire.Reader(frame);
        int type = r.u32Int();
        long version = r.u32();
        return switch (type) {
            case Msg.HELLO -> {
                long protocol = r.u32();
                String mod = r.str();
                int n = r.count();
                List<String> wants = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    wants.add(r.str());
                }
                yield new Msg.Hello(protocol, mod, List.copyOf(wants));
            }
            case Msg.REQUEST -> new Msg.Request(r.u32(), r.u32(), r.str());
            default -> new Msg.Unknown(type, version);
        };
    }

    private static Msg.Welcome welcome(Wire.Reader r) throws Wire.DecodeException {
        long protocol = r.u32();
        boolean supported = r.bool();
        long time = r.s64();
        int n = r.count();
        List<String> features = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            features.add(r.str());
        }
        long flush = r.u32();
        long lookups = r.u32();
        return new Msg.Welcome(protocol, supported, time, List.copyOf(features), flush, lookups);
    }

    /**
     * Each element is a length-delimited inner frame, so one that is broken or
     * of an unknown type is skipped on its own rather than costing the rest of
     * the snapshot. Nested snapshots and non-state frames are not allowed in
     * one (the protocol says types 3-10) and are dropped the same way.
     */
    private static Msg.Snapshot snapshot(Wire.Reader r) throws Wire.DecodeException {
        int n = r.count();
        List<Msg> frames = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] inner = r.bytes();
            try {
                Wire.Reader ir = new Wire.Reader(inner);
                int type = ir.u32Int();
                ir.u32();
                if (type >= Msg.BREAKS && type <= Msg.AREA) {
                    frames.add(state(type, ir));
                }
            } catch (Wire.DecodeException e) {
                // this one inner frame is dropped; the snapshot stands
            }
        }
        return new Msg.Snapshot(List.copyOf(frames));
    }

    private static Msg state(int type, Wire.Reader r) throws Wire.DecodeException {
        switch (type) {
            case Msg.BREAKS -> {
                long count = r.u32();
                long value = r.s64();
                int n = r.count();
                List<Msg.BlockCount> per = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    per.add(new Msg.BlockCount(r.str(), r.u32()));
                }
                return new Msg.Breaks(count, value, List.copyOf(per));
            }
            case Msg.INCOME -> {
                return new Msg.Income(r.str(), r.str(), r.s64(), r.s64());
            }
            case Msg.BALANCE -> {
                long bal = r.s64();
                int n = r.count();
                List<Msg.Extra> extras = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    extras.add(new Msg.Extra(r.str(), r.str()));
                }
                return new Msg.Balance(bal, List.copyOf(extras));
            }
            case Msg.PROGRESS -> {
                return new Msg.Progress(r.str(), r.str(), r.s64(), r.u32(), r.s64());
            }
            case Msg.BOOSTS -> {
                int n = r.count();
                List<Msg.Boost> boosts = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    boosts.add(new Msg.Boost(r.str(), r.str(), r.str(), r.s64()));
                }
                return new Msg.Boosts(List.copyOf(boosts));
            }
            case Msg.SKILL -> {
                return new Msg.Skill(r.str(), r.str(), r.u32(), r.s64(), r.s64());
            }
            case Msg.CONTRACT -> {
                return new Msg.Contract(r.u32(), r.str(), r.u32(), r.u32());
            }
            case Msg.AREA -> {
                return new Msg.Area(r.str());
            }
            default -> throw new Wire.DecodeException("not a state frame: " + type);
        }
    }

    private static Msg.Response response(Wire.Reader r) throws Wire.DecodeException {
        long reqId = r.u32();
        long status = r.u32();
        long retry = r.s64();
        if (status != Msg.Response.OK) {
            return Msg.Response.status(reqId, status, retry);
        }
        String name = r.str();
        String uuid = r.str();
        boolean online = r.bool();
        int n = r.count();
        List<Msg.Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Msg.Row(r.str(), r.str(), r.str()));
        }
        return new Msg.Response(reqId, status, retry, name, uuid, online, List.copyOf(rows));
    }

    // ------------------------------------------------------------ encode

    /** Any known message as a version-1 frame. {@link Msg.Unknown} cannot be encoded. */
    public static byte[] encode(Msg m) {
        Wire.Writer w = new Wire.Writer();
        if (m instanceof Msg.Unknown) {
            throw new IllegalArgumentException("an unknown frame cannot be re-encoded");
        }
        w.u32(m.type()).u32(VERSION);
        body(w, m);
        return w.toByteArray();
    }

    private static void body(Wire.Writer w, Msg m) {
        switch (m) {
            case Msg.Welcome x -> {
                w.u32(x.protocol()).bool(x.supported()).s64(x.serverTimeMs()).count(x.features().size());
                for (String f : x.features()) {
                    w.str(f);
                }
                w.u32(x.flushMs()).u32(x.lookupsPerMinute());
            }
            case Msg.Snapshot x -> {
                w.count(x.frames().size());
                for (Msg f : x.frames()) {
                    w.bytes(encode(f));
                }
            }
            case Msg.Breaks x -> {
                w.u32(x.count()).s64(x.valueCents()).count(x.perBlock().size());
                for (Msg.BlockCount b : x.perBlock()) {
                    w.str(b.blockKey()).u32(b.count());
                }
            }
            case Msg.Combat x -> {
                w.u32(x.kills()).s64(x.valueCents()).count(x.perMob().size());
                for (Msg.MobCount mob : x.perMob()) {
                    w.str(mob.mobKey()).u32(mob.count());
                }
            }
            case Msg.Income x -> w.str(x.sourceId()).str(x.displayName()).s64(x.amountCents()).s64(x.serverTimeMs());
            case Msg.Balance x -> {
                w.s64(x.balanceCents()).count(x.extras().size());
                for (Msg.Extra e : x.extras()) {
                    w.str(e.key()).str(e.display());
                }
            }
            case Msg.Progress x -> w.str(x.rankDisplay()).str(x.nextRankDisplay()).s64(x.nextRankCostCents())
                    .u32(x.prestige()).s64(x.blocksMinedTotal());
            case Msg.Boosts x -> {
                w.count(x.boosts().size());
                for (Msg.Boost b : x.boosts()) {
                    w.str(b.id()).str(b.name()).str(b.multiplierText()).s64(b.expiresAtMs());
                }
            }
            case Msg.Skill x -> w.str(x.skillId()).str(x.displayName()).u32(x.level()).s64(x.xpInLevel())
                    .s64(x.xpForLevel());
            case Msg.Contract x -> w.u32(x.state()).str(x.target()).u32(x.tier()).u32(x.progressPermille());
            case Msg.Area x -> w.str(x.name());
            case Msg.Response x -> {
                w.u32(x.reqId()).u32(x.status()).s64(x.retryAfterMs());
                if (x.status() == Msg.Response.OK) {
                    w.str(x.name()).str(x.uuid()).bool(x.online()).count(x.rows().size());
                    for (Msg.Row row : x.rows()) {
                        w.str(row.group()).str(row.label()).str(row.value());
                    }
                }
            }
            case Msg.Hello x -> {
                w.u32(x.protocol()).str(x.modVersion()).count(x.wants().size());
                for (String s : x.wants()) {
                    w.str(s);
                }
            }
            case Msg.Request x -> w.u32(x.reqId()).u32(x.kind()).str(x.query());
            case Msg.Unknown x -> throw new IllegalArgumentException("unknown frame");
        }
    }
}
