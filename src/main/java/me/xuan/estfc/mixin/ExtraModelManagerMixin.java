package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.misc.client.IMapSlice;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.xuan.estfc.Config;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修复客户端 ES 雪渲染管线中对 TFC 固体方块的 flag/SnowyRemover/isSnowyBlock 处理。
 *
 * <h3>修复</h3>
 * 1. getLayer flag: TFC 固体方块 flag=0 → 返回 1 通过检查
 * 2. getLayer 雪深: biome 缓存深度 → TFC 瞬时温度 tempToSnowDepth
 * 3. maySnowyAt SnowyRemover: NONE_SNOWY + isSnowyBlock=true → 绕过返回 -1
 * 4. maySnowyAt isSnowyBlock: false + 固体方块 → TFC 温度 ≤ 0 → 返回 true（核心！）
 *
 * <h3>诊断</h3>
 * - findModel RETURN: 前60次 TFC 方块 — flag, result, status, isSnowy, height
 * - maySnowyAt RETURN: 前50次 — status, isSnowy, result
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class ExtraModelManagerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED_GETLAYER = false;
    @Unique
    private static boolean LOGGED_FINDMODEL_FLAG = false;
    @Unique
    private static int FIND_MODEL_DIAG_COUNT = 0;
    @Unique
    private static int MAY_SNOWY_AT_DIAG_COUNT = 0;

    /**
     * 修复 #1: 让固体地面方块通过 getLayer 的 flag <= FLAG_NONE 检查。
     * TFC 地面方块 flag=0(FLAG_NONE) → 原版直接 return 0 → 客户端不渲染雪层。
     */
    @WrapOperation(
        method = "getLayer",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/map/MapChecker;getDefaultBlockTypeFlag(Lnet/minecraft/world/level/block/state/BlockState;)I")
    )
    private static int estfc$fixGroundFlagInGetLayer(BlockState state, Operation<Integer> original) {
        int flag = original.call(state);
        if (flag == MapChecker.FLAG_NONE && state.blocksMotion()) {
            return 1; // 非零 → 通过 getLayer 的 flag<=0 检查
        }
        return flag;
    }

    /**
     * 修复 #2: 替换 biome 级雪深为 TFC 温度 → 雪深。
     */
    @WrapOperation(
        method = "getLayer",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I")
    )
    private static int estfc$replaceDepthForLayer(Level level, Biome biome, Operation<Integer> original,
                                                   BlockAndTintGetter blockAndTintGetter, BlockPos.MutableBlockPos pos) {
        try {
            ClimateModel model = Climate.get(level);
            if (model.supportsRain()) {
                float temp = model.getInstantTemperature(level, pos);
                int depth = WeatherBridge.tempToSnowDepth(temp);

                if (!LOGGED_GETLAYER) {
                    LOGGED_GETLAYER = true;
                    LOG.info("[estfc:getLayer] TFC depth={} temp={} at ({},{},{})",
                        depth, temp, pos.getX(), pos.getY(), pos.getZ());
                }
                return depth;
            }
        } catch (Exception e) {
            if (!LOGGED_GETLAYER) {
                LOGGED_GETLAYER = true;
                LOG.warn("[estfc:getLayer] TFC unavailable, falling back to biome depth", e);
            }
        }
        return original.call(level, biome);
    }

    // ==================== findModel flag 诊断 ====================

    /**
     * 诊断: 记录 findModel 中 TFC 固体方块的 flag 值。
     */
    @WrapOperation(
        method = "findModel",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/map/MapChecker;getDefaultBlockTypeFlag(Lnet/minecraft/world/level/block/state/BlockState;)I")
    )
    private static int estfc$diagFlagInFindModel(BlockState state, Operation<Integer> original) {
        int flag = original.call(state);
        if (flag == MapChecker.FLAG_NONE && state.blocksMotion() && !LOGGED_FINDMODEL_FLAG) {
            LOGGED_FINDMODEL_FLAG = true;
            LOG.warn("[estfc:diag] findModel flag=0 (FLAG_NONE) for solid block {} — will return null!",
                state.getBlock().builtInRegistryHolder().key().location());
        }
        return flag;
    }

    // ==================== findModel 返回值诊断 (渲染端核心) ====================

    /**
     * 诊断: 记录 findModel 的返回值，仅针对 TFC 方块。
     * 对比边缘方块 vs 非边缘方块的返回结果差异。
     */
    @Inject(method = "findModel", at = @At("RETURN"))
    private static void estfc$diagFindModelReturn(
        BlockAndTintGetter blockAndTintGetter, BlockPos pos, BlockState state,
        RandomSource random, long seed, BlockPos.MutableBlockPos checkPos,
        CallbackInfoReturnable<BakedModel> cir) {
        if (FIND_MODEL_DIAG_COUNT >= 60) return;
        String ns = state.getBlock().builtInRegistryHolder().key().location().getNamespace();
        if (!"tfc".equals(ns)) return;
        FIND_MODEL_DIAG_COUNT++;

        BakedModel result = cir.getReturnValue();
        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        IMapSlice mapSlice = (blockAndTintGetter instanceof IMapSlice ms) ? ms : null;
        int snowyStatus = mapSlice != null ? mapSlice.getSnowyStatus(pos) : -99;
        boolean isSnowyBlock = mapSlice != null && mapSlice.isSnowyBlock(pos);
        int blockHeight = mapSlice != null ? mapSlice.getBlockHeight(pos) : -99;

        LOG.warn("[estfc:diag] findModel[{}] at ({},{},{}) flag={} result={} status={} isSnowy={} height={} block={}",
            FIND_MODEL_DIAG_COUNT, pos.getX(), pos.getY(), pos.getZ(),
            flag, result != null ? "MODEL" : "null",
            snowyStatus, isSnowyBlock, blockHeight,
            state.getBlock().builtInRegistryHolder().key().location());
    }

    // ==================== maySnowyAt SnowyRemover 诊断 + 绕过 ====================

    /**
     * 诊断: 记录 maySnowyAt 中 getSnowyStatus 的返回值。
     * 同时绕过 SnowyRemover.NONE_SNOWY 误判。
     */
    @WrapOperation(
        method = "maySnowyAt",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/api/misc/client/IMapSlice;getSnowyStatus(Lnet/minecraft/core/BlockPos;)I")
    )
    private static int estfc$fixSnowyStatusInMaySnowyAt(IMapSlice mapSlice, BlockPos pos, Operation<Integer> original) {
        int status = original.call(mapSlice, pos);
        boolean inSnowyKeeper = mapSlice.isSnowyBlock(pos);
        if (status == 1 && inSnowyKeeper) {
            LOG.warn("[estfc:diag] maySnowyAt NONE_SNOWY but isSnowyBlock=true at ({},{},{}) — bypass",
                pos.getX(), pos.getY(), pos.getZ());
            return -1;
        }
        if (status == 1 && !inSnowyKeeper) {
            LOG.warn("[estfc:diag] maySnowyAt NONE_SNOWY and isSnowyBlock=false at ({},{},{}) — NO SNOW",
                pos.getX(), pos.getY(), pos.getZ());
        }
        return status;
    }

    // ==================== maySnowyAt isSnowyBlock TFC 回退 ====================

    /**
     * 核心修复: 当 isSnowyBlock() 返回 false（SnowyStatusKeeper 无数据——区块刚加载、
     * 高度图与实际方块不符等场景），用 TFC 瞬时温度直接判断是否应有雪。
     *
     * 安全: findModel 在调用 maySnowyAt 前已通过 cut &gt; 1 检查过滤地下方块，
     * 因此本 fallback 不会误给地下方块覆雪。
     */
    @WrapOperation(
        method = "maySnowyAt",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/api/misc/client/IMapSlice;isSnowyBlock(Lnet/minecraft/core/BlockPos;)Z")
    )
    private static boolean estfc$tfcSnowyFallback(IMapSlice mapSlice, BlockPos pos, Operation<Boolean> original,
                                                   Level level, IMapSlice ms, BlockState state, BlockPos checkPos,
                                                   RandomSource random, long seed) {
        boolean result = original.call(mapSlice, pos);
        if (!result && state.blocksMotion()) {
            try {
                ClimateModel model = Climate.get(level);
                if (model.supportsRain()) {
                    float temp = model.getInstantTemperature(level, pos);
                    if (temp <= Config.snowMeltTemp()) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // TFC unavailable — fall through to original result
            }
        }
        return result;
    }

    // ==================== maySnowyAt 返回值诊断 ====================

    /**
     * 诊断: 记录 maySnowyAt 的返回值及关键输入参数。
     * 仅记录前 50 次调用。
     */
    @Inject(method = "maySnowyAt", at = @At("RETURN"))
    private static void estfc$diagMaySnowyAtReturn(
        Level level, IMapSlice mapSlice, BlockState state, BlockPos checkPos,
        RandomSource random, long seed, CallbackInfoReturnable<Boolean> cir) {
        if (MAY_SNOWY_AT_DIAG_COUNT >= 50) return;
        MAY_SNOWY_AT_DIAG_COUNT++;

        int snowyStatus = mapSlice != null ? mapSlice.getSnowyStatus(checkPos) : -99;
        boolean isSnowy = mapSlice != null && mapSlice.isSnowyBlock(checkPos);
        int blockHeight = mapSlice != null ? mapSlice.getBlockHeight(checkPos) : -99;

        LOG.warn("[estfc:diag] maySnowyAt[{}] at ({},{},{}) status={} isSnowy={} result={} height={} posY={} block={}",
            MAY_SNOWY_AT_DIAG_COUNT,
            checkPos.getX(), checkPos.getY(), checkPos.getZ(),
            snowyStatus, isSnowy, cir.getReturnValue(),
            blockHeight, checkPos.getY(),
            state.getBlock().builtInRegistryHolder().key().location());
    }
}
