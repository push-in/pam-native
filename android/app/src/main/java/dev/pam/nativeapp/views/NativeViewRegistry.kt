package dev.pam.nativeapp.views

import android.content.Context
import android.view.View
import dev.pam.nativeapp.protocol.WireValue
import java.util.WeakHashMap

class NativeViewRegistry(private val context: Context) : AutoCloseable {
    private val factories = GeneratedPamViews.create(context)
    private val owners = WeakHashMap<View, NativeViewFactory>()

    fun create(
        name: String,
        emit: (ByteArray) -> Unit,
    ): View {
        val factory = factories[name] ?: error("Unknown generated native view $name")
        return factory.create(context = context, emit = emit).also { view ->
            owners[view] = factory
        }
    }

    fun update(view: View, properties: Map<String, WireValue>) {
        val factory = owners[view] ?: error("Native view has no registered factory")
        factory.update(view, properties)
    }

    fun release(view: View) {
        owners.remove(view)?.release(view)
    }

    override fun close() {
        owners.toMap().forEach { (view, factory) -> factory.release(view) }
        owners.clear()
        factories.values.filterIsInstance<AutoCloseable>().forEach { factory ->
            runCatching { factory.close() }
        }
    }
}
