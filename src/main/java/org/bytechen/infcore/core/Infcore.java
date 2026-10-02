package org.bytechen.infcore.core;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.bytechen.infcore.core.client.ClientPacketHandlers;
import org.bytechen.infcore.core.command.DifficultyCommand;
import org.bytechen.infcore.core.network.NetworkHelper;
import org.bytechen.infcore.core.util.ThrottledLogger;
import org.slf4j.Logger;

@Mod(Infcore.MODID)
public class Infcore {

    public static final String MODID = "infcore";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 高频明细日志开关。
     * <p>
     * 方块扩散、实体进化等事件在感染爆发时每秒可发生成百上千次，默认只在 TRACE 级别输出明细、
     * 并以 30 秒为窗口聚合成一条摘要（见 {@code ThrottledLogger}）。
     * 需要在控制台逐条观察这些事件时，给游戏加启动参数 {@code -Dinfcore.verbose=true} 即可。
     */
    public static final boolean VERBOSE = Boolean.parseBoolean(System.getProperty("infcore.verbose", "false"));

    public Infcore() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        NetworkHelper.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("InfectionCoreFramework common setup complete.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("InfectionCoreFramework server starting.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 把节流器窗口内还没汇总的事件数补一条日志，避免关服时丢掉统计
        ThrottledLogger.flushAll();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DifficultyCommand.register(event.getDispatcher());
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("InfectionCoreFramework client setup.");
            ClientPacketHandlers.init();
        }
    }
}
