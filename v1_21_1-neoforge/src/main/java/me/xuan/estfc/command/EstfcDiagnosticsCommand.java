package me.xuan.estfc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import me.xuan.estfc.weather.BiomeCoverageCache;
import me.xuan.estfc.weather.BiomeSampleCache;
import me.xuan.estfc.weather.BiomeSnowSeed;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;

/** 临时诊断命令：/estfc diag [x z] */
public final class EstfcDiagnosticsCommand {

    private EstfcDiagnosticsCommand() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("estfc")
            .then(Commands.literal("diag")
                .executes(ctx -> diag(ctx.getSource(), null))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> diag(ctx.getSource(),
                            new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), 0,
                                IntegerArgumentType.getInteger(ctx, "z"))))))));
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
        ClimateModel model = Climate.get(level);
        float temp = model.getInstantTemperature(level, pos);
        float rainfall = model.getAverageRainfall(level, pos);
        int biomeDepth = ClimateQuery.getBiomeDepth(level, biome);
        Float cache = BiomeCoverageCache.get(level, biome);
        BlockPos sample = BiomeSampleCache.get(level, biome);
        WeatherManager.BiomeWeather weather = WeatherManager.getBiomeWeather(level, biome);

        float sampleTemp = sample == null ? Float.NaN : model.getInstantTemperature(level, sample);
        float sampleRain = sample == null ? Float.NaN : model.getAverageRainfall(level, sample);
        int depth = SnowDepthRules.depthFromCoverage(temp, biomeDepth, rainfall);

        source.sendSuccess(() -> Component.literal(
            "[estfc] dim=" + level.dimension().location()
                + " pos=" + pos + " biome=" + biome
                + "\n  temp=" + temp + " rain=" + rainfall
                + " factor=" + SnowDepthRules.rainfallFactor(rainfall)
                + " precip=" + ClimateQuery.isPrecipitating(level, pos)
                + "\n  biomeDepth=" + biomeDepth + " cache=" + cache + " climateOnly=" + BiomeCoverageCache.isClimateOnly(level, biome)
                + " depthFromCoverage=" + depth
                + "\n  sample=" + sample + " sampleTemp=" + sampleTemp
                + " sampleRain=" + sampleRain
                + " weatherDepth=" + (weather == null ? "null" : (weather.getSnowDepth() & 0xFF))
        ), false);
        return 1;
    }
}
