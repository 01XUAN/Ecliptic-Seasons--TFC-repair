package me.xuan.estfc.data;

import com.teamtea.eclipticseasons.api.data.season.SnowDefinition;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.ClientModelDefinitions;
import com.teamtea.eclipticseasons.data.api.provider.base.ESClientDataMapProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.teamtea.eclipticseasons.client.reload.ClientJsonCacheListener.DIRECTORY_SNOW_DEFINITION;

public class TfcSnowDefinitionProvider extends ESClientDataMapProvider<SnowDefinition> {

    private static final String TFC = "tfc";

    public TfcSnowDefinitionProvider(PackOutput output, ExistingFileHelper helper,
                                      CompletableFuture<HolderLookup.Provider> registries) {
        super(output, "estfc", helper, registries, DIRECTORY_SNOW_DEFINITION, SnowDefinition.CODEC);
    }

    @Override
    protected void gather(HolderLookup.Provider provider) {
        HolderLookup.RegistryLookup<Block> blocks = provider.lookupOrThrow(Registries.BLOCK);

        // ── 地面装饰物 ──
        String[] groundcoverTypes = {
            "bone", "clam", "driftwood", "feather", "flint", "guano", "humus",
            "mollusk", "mussel", "pinecone", "pumice", "rotten_flesh",
            "salt_lick", "seaweed", "sea_urchin", "stick"
        };
        for (String type : groundcoverTypes) {
            addSnowDef(blocks, "groundcover/" + type,
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
        }

        // ── 散落岩石 ──
        String[] rockTypes = {
            "granite", "diorite", "gabbro", "shale", "claystone", "limestone",
            "conglomerate", "dolomite", "chert", "chalk", "tuff", "rhyolite",
            "basalt", "andesite", "dacite", "quartzite", "slate", "phyllite",
            "schist", "gneiss", "marble"
        };
        for (String rock : rockTypes) {
            addSnowDef(blocks, "rock/loose/" + rock,
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
            addSnowDef(blocks, "rock/mossy_loose/" + rock,
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
        }

        // ── 落叶堆 ──
        String[] woodTypes = {
            "acacia", "ash", "aspen", "birch", "blackwood", "chestnut",
            "douglas_fir", "hickory", "kapok", "mangrove", "maple", "oak",
            "palm", "pine", "rosewood", "sequoia", "spruce", "sycamore",
            "white_cedar", "willow"
        };
        for (String wood : woodTypes) {
            addSnowDef(blocks, "wood/fallen_leaves/" + wood,
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
        }

        // ── 树枝 ──
        for (String wood : woodTypes) {
            addSnowDef(blocks, "wood/twig/" + wood,
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
        }

        // ── 高山矮曲林 (Krummholz) ──
        String[] krummholzWoods = {"pine", "spruce", "douglas_fir", "white_cedar", "aspen"};
        for (String wood : krummholzWoods) {
            addSnowDef(blocks, "plant/" + wood + "_krummholz",
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
        }

        // ── 野生作物 ──
        String[] cropTypes = {
            "alfalfa", "barley", "beet", "cabbage", "canola", "carrot", "cassava",
            "garlic", "green_bean", "jute", "lentil", "maize", "melon", "oat",
            "onion", "papyrus", "peanut", "potato", "pumpkin", "radish",
            "red_bell_pepper", "rice", "rye", "soybean", "squash", "sugarcane",
            "tomato", "wheat", "yellow_bell_pepper"
        };
        for (String crop : cropTypes) {
            addSnowDef(blocks, "wild_crop/" + crop,
                SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .mid(ClientModelDefinitions.OVERLAY_TINY)
                    .build());
        }
    }

    private void addSnowDef(HolderLookup.RegistryLookup<Block> blocks, String tfcPath,
                            SnowDefinition.Info info) {
        ResourceLocation id = new ResourceLocation(TFC, tfcPath);
        Optional<Holder.Reference<Block>> holder = blocks.get(ResourceKey.create(Registries.BLOCK, id));
        if (holder.isPresent()) {
            add(tfcPath.replace('/', '_'),
                SnowDefinition.builder()
                    .blocks(HolderSet.direct(holder.get()))
                    .info(info)
                    .build());
        }
    }
}
