package me.xuan.estfc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import me.xuan.estfc.weather.BiomeCoverageCache;
import me.xuan.estfc.weather.BiomeSampleCache;
import me.xuan.estfc.weather.BiomeSnowSeed;
import me.xuan.estfc.weather.ChunkSnowScheduler;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;

/** 诊断命令（debugLogs 开启时注册）：/estfc diag [x z] / /estfc refresh [radius] */
public final class EstfcDiagnosticsCommand {

    private EstfcDiagnosticsCommand() {}

    public static void register(RegisterCommandsEvent event) {
        if (!Config.debugLogs()) return;
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("estfc")
            .then(Commands.literal("diag")
                .executes(ctx -> diag(ctx.getSource(), null))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> diag(ctx.getSource(),
                            new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), 0,
                                IntegerArgumentType.getInteger(ctx, "z")))))))
            .then(Commands.literal("refresh")
                .executes(ctx -> refresh(ctx.getSource(), 2))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 16))
                    .executes(ctx -> refresh(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    /** 按当前公式刷新玩家周围已加载区块（进入调度队列）。 */
    private static int refresh(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        int chunks = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunk(cx + dx, cz + dz);
                com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap map =
                    MapChecker.getChunkInfoMapOrCreate(level, chunk.getPos());
                if (map == null) continue;
                ChunkSnowScheduler.enqueue(level, map, chunk.getPos(), true);
                chunks++;
            }
        }
        int queued = chunks;
        source.sendSuccess(() -> Component.literal("[estfc] queued chunks=" + queued), false);
        return 1;
    }

    private static int diag(CommandSourceStack source, @Nullable BlockPos input) {
        ServerLevel level = source.getLevel();
        BlockPos base = input;
        if (base == null) {
            if (source.getEntity() == null) return 0;
            base = BlockPos.containing(source.getPosition());
        }

        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, base.getX(), base.getZ());
        BlockPos pos = new BlockPos(base.getX(), surface, base.getZ());
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        BlockState state = level.getBlockState(pos);
        SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);

        Biome biome = level.getBiome(pos).value();
        BiomeSnowSeed.ensureSeeded(level, biome, pos);
        float temp = ClimateQuery.instantTemperature(level, pos);
        float rainfall = ClimateQuery.averageRainfall(level, pos);
        int biomeDepth = ClimateQuery.getBiomeDepth(level, biome);
        Float cache = BiomeCoverageCache.get(level, biome);
        BlockPos sample = BiomeSampleCache.get(level, biome);
        WeatherManager.BiomeWeather weather = WeatherManager.getBiomeWeather(level, biome);

        float sampleTemp = sample == null ? Float.NaN : ClimateQuery.instantTemperature(level, sample);
        float sampleRain = sample == null ? Float.NaN : ClimateQuery.averageRainfall(level, sample);
        int depth = SnowDepthRules.depthFromCoverage(temp, biomeDepth, rainfall);
        int snowy = keeper == null ? -1 : keeper.getPosMap().size();
        boolean snowHere = keeper != null && keeper.isSnowyBlock(pos);

        source.sendSuccess(() -> Component.literal(
            "[estfc] dim=" + level.dimension().location()
                + " pos=" + pos + " biome=" + biome
                + "\n  temp=" + temp + " rain=" + rainfall
                + " factor=" + SnowDepthRules.rainfallFactor(rainfall)
                + " precip=" + ClimateQuery.isPrecipitating(level, pos)
                + "\n  biomeDepth=" + biomeDepth + " cache=" + cache + " climateOnly=" + BiomeCoverageCache.isClimateOnly(level, biome)
                + " depthFromCoverage=" + depth
                + " snowy=" + snowy + " snowHere=" + snowHere
                + "\n  sample=" + sample + " sampleTemp=" + sampleTemp
                + " sampleRain=" + sampleRain
                + " weatherDepth=" + (weather == null ? "null" : (weather.getSnowDepth() & 0xFF))
        ), false);
        return 1;
    }
}
