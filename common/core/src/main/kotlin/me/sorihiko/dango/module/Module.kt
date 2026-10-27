package me.sorihiko.dango.module

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.ModuleToggleEvent
import me.sorihiko.dango.module.values.ModuleValue
import java.lang.reflect.Field

abstract class Module : Listener {
    val category = javaClass.getAnnotation(ModuleMeta::class.java).category
    val name = javaClass.getAnnotation(ModuleMeta::class.java).name
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                DangoMusic.callEvent(ModuleToggleEvent.Enable(this))
                onEnable()
            }else {
                onDisable()
                DangoMusic.callEvent(ModuleToggleEvent.Disable(this))
            }
        }

    /** 配置与设置页共用，包含父类中声明的选项。子类同名字段优先。 */
    fun valueFields(): List<Field> =
        generateSequence<Class<*>>(javaClass) { it.superclass }
            .takeWhile { it != Module::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.isAnnotationPresent(ModuleValue::class.java) }
            .distinctBy { it.name }
            .onEach { it.isAccessible = true }
            .toList()

    open fun init() {}
    protected open fun onEnable() {}
    protected open fun onDisable() {}
}