package me.xuan.estfc.data;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@SuppressWarnings("deprecation")
@EventBusSubscriber(modid = "estfc", bus = EventBusSubscriber.Bus.MOD)
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