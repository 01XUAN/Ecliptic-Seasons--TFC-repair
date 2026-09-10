package me.xuan.estfc.data;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.data.event.GatherDataEvent;

@SuppressWarnings("deprecation")
@Mod.EventBusSubscriber(modid = "estfc", bus = Mod.EventBusSubscriber.Bus.MOD)
public class DataGenerators {

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        var generator = event.getGenerator();
        var output = generator.getPackOutput();
        var helper = event.getExistingFileHelper();
        var registries = event.getLookupProvider();

        generator.addProvider(true, new TfcSnowDefinitionProvider(output, helper, registries));
    }
}