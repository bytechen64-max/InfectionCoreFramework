package org.bytechen.infcore.core.datagen;

import net.minecraft.data.PackOutput;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 数据生成事件订阅器。
 * <p>
 * 在运行 {@code runData} 配置时注册 {@link EvolutionDataProvider}。
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DataGenEvent {

    private DataGenEvent() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        PackOutput packOutput = event.getGenerator().getPackOutput();

        event.getGenerator().addProvider(
                event.includeServer(),
                new EvolutionDataProvider(packOutput)
        );
    }
}
