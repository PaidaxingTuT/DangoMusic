package me.sorihiko.dango.forge;

import me.sorihiko.dango.DangoMusic;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = "dangomusic", name = "Dango Music", version = "1.0.0",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class DangoMusicMod {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        DangoMusic.start();
        ForgeBridge bridge = new ForgeBridge();
        MinecraftForge.EVENT_BUS.register(bridge);
        // 老版本 Forge 里 Tick 事件走 FML 自己的总线
        if (FMLCommonHandler.instance().bus() != MinecraftForge.EVENT_BUS) {
            FMLCommonHandler.instance().bus().register(bridge);
        }
    }
}
