package me.sorihiko.dango.utils.render.img

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.ResourceLocation

class MCResImage(
    private val resource: ResourceLocation,
) : UiImage {

    companion object {
        fun of(loc: String): MCResImage {
            return MCResImage(ResourceLocation(loc))
        }
    }

    override fun bind() {
        GlStateManager.bindTexture(0)
        Minecraft.getMinecraft()
            .textureManager
            .bindTexture(resource)
    }
}