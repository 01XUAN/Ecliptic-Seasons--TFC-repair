package me.xuan.estfc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.compat.distanthorizons.DHTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.reflect.Method;

/**
 * 修复 DH 远景的三个问题。
 *
 * <h3>修复 #1：地面方块无雪</h3>
 * DHTool.computeBaseColor 的 flag!=0 检查跳过了 FLAG_NONE 的地面方块。
 * 对 solid 方块返回 1 使其通过检查。
 *
 * <h3>修复 #2：computeBaseColor 中 McObjectConverter_neoforge 不存在</h3>
 * ES 调用 {@code McObjectConverter_neoforge.Convert(DhBlockPos)}，
 * 但 DH 3.2.0-b 已将其重命名为 {@code McObjectConverter.Convert(DhBlockPos)}。
 * 拦截 computeBaseColor 中的旧调用，用反射 DhBlockPos.getX/Y/Z 构造 BlockPos。
 *
 * <h3>修复 #3：shouldFrozen 中 McObjectConverter_neoforge 不存在</h3>
 * 与修复 #2 同根因。shouldFrozen 也调用了已不存在的
 * {@code McObjectConverter_neoforge.Convert(DhBlockPos)}。
 * 当用户开启 frozenWater 配置时，此路径会在水方块 LOD 渲染时崩溃。
 */
@Mixin(value = DHTool.class, remap = false)
public abstract class DHToolMixin {

    // ========================================================================
    // 修复 #1：地面方块 flag 修复
    // ========================================================================

    @WrapOperation(
        method = "computeBaseColor",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/map/MapChecker;getDefaultBlockTypeFlag(Lnet/minecraft/world/level/block/state/BlockState;)I")
    )
    private static int estfc$fixGroundSnowInDH(BlockState state, Operation<Integer> original) {
        int flag = original.call(state);
        if (flag == MapChecker.FLAG_NONE && state.blocksMotion()) {
            return 1;
        }
        return flag;
    }

    // ========================================================================
    // 修复 #2 & #3：McObjectConverter_neoforge → McObjectConverter API 桥接
    // 覆盖 computeBaseColor 和 shouldFrozen 两个方法中的旧 API 调用。
    // ========================================================================

    /** DhBlockPos 的 getX/getY/getZ 方法，类加载时解析一次，避免 DH 热路径上重复 getMethod() */
    @Unique
    private static final Method DH_GET_X;
    @Unique
    private static final Method DH_GET_Y;
    @Unique
    private static final Method DH_GET_Z;

    static {
        Method mx = null, my = null, mz = null;
        try {
            Class<?> c = Class.forName("com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos");
            mx = c.getMethod("getX");
            my = c.getMethod("getY");
            mz = c.getMethod("getZ");
        } catch (Exception ignored) {
            // DH 未安装时保持 null，bridge 方法不会被调用（ES 不会启用 DH compat）
        }
        DH_GET_X = mx;
        DH_GET_Y = my;
        DH_GET_Z = mz;
    }

    /**
     * 拦截 {@code McObjectConverter_neoforge.Convert(DhBlockPos)} 调用。
     * 使用类加载时缓存的 Method 引用，避免每帧数万次 {@code getMethod()} 查找。
     */
    @WrapOperation(
        method = "computeBaseColor",
        at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/common/wrappers/McObjectConverter_neoforge;Convert(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos;)Lnet/minecraft/core/BlockPos;"),
        remap = false
    )
    private static BlockPos estfc$bridgeMcObjectConverter(Object dhBlockPos, Operation<BlockPos> original) {
        if (DH_GET_X == null) {
            // DH 未安装时的防御性回退（正常路径不会到达这里）
            return BlockPos.ZERO;
        }
        try {
            int x = (int) DH_GET_X.invoke(dhBlockPos);
            int y = (int) DH_GET_Y.invoke(dhBlockPos);
            int z = (int) DH_GET_Z.invoke(dhBlockPos);
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return BlockPos.ZERO;
        }
    }

    // ========================================================================
    // 修复 #3：shouldFrozen 的 McObjectConverter_neoforge 调用
    // ========================================================================

    /**
     * 拦截 shouldFrozen 中的 {@code McObjectConverter_neoforge.Convert(DhBlockPosMutable)} 调用。
     * 与 computeBaseColor 的修复 (#2) 使用相同的反射桥接策略。
     * DhBlockPosMutable 继承自 DhBlockPos，getX/getY/getZ 均可用。
     */
    @WrapOperation(
        method = "shouldFrozen",
        at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/common/wrappers/McObjectConverter_neoforge;Convert(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos;)Lnet/minecraft/core/BlockPos;"),
        remap = false
    )
    private static BlockPos estfc$bridgeMcObjectConverterInShouldFrozen(Object dhBlockPos, Operation<BlockPos> original) {
        return estfc$bridgeMcObjectConverter(dhBlockPos, original);
    }
}
