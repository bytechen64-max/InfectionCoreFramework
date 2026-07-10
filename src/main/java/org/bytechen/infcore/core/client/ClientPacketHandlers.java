package org.bytechen.infcore.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.network.NetworkEvent;
import org.bytechen.infcore.core.capability.AbstractCapability;
import org.bytechen.infcore.core.capability.CapabilityRegistry;
import org.bytechen.infcore.core.network.PacketSyncCapability;

/**
 * 客户端数据包处理器。
 * <p>
 * 仅在物理客户端上加载，通过 {@link PacketSyncCapability#CLIENT_HANDLER} 注入。
 * 在客户端设置阶段由 {@code ClientModEvents} 调用 {@link #init()} 完成绑定。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    /**
     * 绑定所有客户端数据包处理器。
     * 在 {@code FMLClientSetupEvent} 中调用。
     */
    public static void init() {
        PacketSyncCapability.CLIENT_HANDLER = ClientPacketHandlers::handleSyncCapability;
    }

    /**
     * 处理能力同步数据包。
     * <p>
     * 根据包中的 entityId 找到对应实体，将同步数据写入其能力实例。
     */
    private static void handleSyncCapability(PacketSyncCapability packet, NetworkEvent.Context context) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        Entity entity = level.getEntity(packet.getEntityId());
        if (entity == null) return;

        Capability<?> cap = CapabilityRegistry.getById(packet.getCapabilityId());
        if (cap == null) return;

        entity.getCapability(cap).ifPresent(capInstance -> {
            if (capInstance instanceof AbstractCapability<?> absCap) {
                absCap.readSyncData(packet.getSyncData());
            }
        });
    }
}
