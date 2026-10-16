package me.sorihiko.dango.forge;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 入口：启动 mod，把 Forge 的事件转成 mod 自己的事件。
 * 其它代码（画图、按键、界面）和 Fabric 版共用。
 */
@Mod("dangomusic")
public class DangoMusicForge {

    public DangoMusicForge() {
        // 纯客户端 mod：在服务器上什么都不做
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Client.init();
        }
    }

    /** 单独放一个类：服务器上不会加载到这些客户端类 */
    private static final class Client {
        static void init() {
            ModernDango.start();
            MinecraftForge.EVENT_BUS.addListener(Client::onClientTick);
            MinecraftForge.EVENT_BUS.addListener(Client::onRenderLevel);
        }

        /** 每个游戏 tick 开头 */
        static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) ModernDango.tick();
        }

        /** 世界画完（手和 HUD 之前），和 Fabric 的 WorldRenderEvents.LAST 对应 */
        static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                ModernDango.worldRenderEnd();
            }
        }
    }
}
