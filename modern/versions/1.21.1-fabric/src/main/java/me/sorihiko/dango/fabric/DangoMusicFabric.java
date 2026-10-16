package me.sorihiko.dango.fabric;

import me.sorihiko.dango.modern.ModernDango;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Fabric 入口：启动 mod，把 Fabric 的事件转成 mod 自己的事件 */
public class DangoMusicFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModernDango.start();
        ClientTickEvents.START_CLIENT_TICK.register(client -> ModernDango.tick());
        WorldRenderEvents.LAST.register(context -> ModernDango.worldRenderEnd());
    }
}
