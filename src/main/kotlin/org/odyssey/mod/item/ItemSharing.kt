package org.odyssey.mod.item

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.item.ItemStack
import org.odyssey.mod.OdysseyDiagnostics
import org.odyssey.mod.network.ItemShare
import org.odyssey.mod.network.ItemShareKind
import org.odyssey.mod.network.MAX_ITEM_SHARES
import java.util.regex.Pattern

internal object ItemSharing {
    private var pendingCaptures = 0
    private val wynntils by lazy { runCatching { WynntilsAccess() }.getOrNull() }

    fun capture(body: List<Component>, content: String, complete: (List<ItemShare>) -> Unit) {
        val items = LinkedHashMap<String, SharedStack>()
        if (FabricLoader.getInstance().isModLoaded("wynntils")) {
            runCatching { wynntils?.decode(content) }.onFailure {
                OdysseyDiagnostics.logger.debug("[Odyssey Mod] Item decoding unavailable", it)
            }.getOrNull()?.forEach { items.putIfAbsent(it.encoded, it) }
        }
        body.forEach { collectNative(it, content, items) }
        val selected = items.values.take(MAX_ITEM_SHARES)
        if (selected.isEmpty()) {
            complete(emptyList())
            return
        }
        if (pendingCaptures >= 2) {
            complete(selected.map { it.preview(null) })
            return
        }
        pendingCaptures += 1
        val previews = ArrayList<ItemShare>()
        fun next(index: Int) {
            if (index == selected.size) {
                pendingCaptures -= 1
                complete(previews)
                return
            }
            val item = selected[index]
            TooltipCapture.capture(item.stack).whenComplete { png, error ->
                Minecraft.getInstance().execute {
                    if (error != null) OdysseyDiagnostics.logger.debug("[Odyssey Mod] Tooltip image unavailable", error)
                    previews.add(item.preview(png))
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    private fun collectNative(component: Component, content: String, items: MutableMap<String, SharedStack>) {
        val hover = component.style.hoverEvent as? HoverEvent.ShowItem
        // Hover styles apply to the complete subtree, including empty wrapper literals.
        val reference = component.string.trim()
        if (hover != null && !reference.isNullOrEmpty() && reference in content && items.size < MAX_ITEM_SHARES) {
            if (items.values.any { it.kind == ItemShareKind.WYNNTILS && it.encoded == reference }) return
            val stack = hover.item.copy()
            val name = stack.hoverName.string
            if (name.isNotEmpty() && name.codePointCount(0, name.length) <= 128 && !name.any(Char::isISOControl)) {
                if (items.values.none { it.encoded == reference && ItemStack.isSameItemSameComponents(it.stack, stack) }) {
                    items["native-${items.size}"] = SharedStack(reference, name, ItemShareKind.WYNNCRAFT, stack)
                }
                return
            }
        }
        component.siblings.forEach { collectNative(it, content, items) }
    }

    private data class SharedStack(val encoded: String, val name: String, val kind: ItemShareKind, val stack: ItemStack) {
        fun preview(png: String?) = ItemShare(kind, encoded, name, stack.hoverName.style.color?.value ?: 0xFFAA00, png)
    }

    // Only custom Wynntils API names are reflected. Minecraft calls remain remapped by Loom.
    private class WynntilsAccess {
        private val model = Class.forName("com.wynntils.core.components.Models").getField("ItemEncoding").get(null)
        private val bufferClass = Class.forName("com.wynntils.utils.EncodedByteBuffer")
        private val fromUtf16 = bufferClass.getMethod("fromUtf16String", String::class.java)
        private val decoder = model.javaClass.getMethod("decodeItemWithTrustedName", bufferClass, String::class.java)
        private val pattern = model.javaClass.getMethod("getEncodedDataPattern").invoke(model) as Pattern
        private val wynnItemClass = Class.forName("com.wynntils.models.items.WynnItem")
        private val fakeStack = Class.forName("com.wynntils.models.items.FakeItemStack")
            .getConstructor(wynnItemClass, String::class.java)
        private val namedItem = Class.forName("com.wynntils.models.items.properties.NamedItemProperty")
        private val getName = namedItem.getMethod("getName")

        fun decode(content: String): List<SharedStack> {
            val matcher = pattern.matcher(content)
            val items = ArrayList<SharedStack>()
            val seen = HashSet<String>()
            while (matcher.find() && items.size < MAX_ITEM_SHARES) {
                val reference = matcher.group()
                if (!seen.add(reference)) continue
                val decoded = runCatching {
                    val buffer = fromUtf16.invoke(null, matcher.group("data"))
                    val result = decoder.invoke(model, buffer, matcher.group("name"))
                    if (result.javaClass.getMethod("hasError").invoke(result) as Boolean) return@runCatching null
                    val item = result.javaClass.getMethod("getValue").invoke(result)
                    if (!namedItem.isInstance(item)) return@runCatching null
                    val name = getName.invoke(item) as String
                    if (name.isEmpty() || name.codePointCount(0, name.length) > 128 || name.any(Char::isISOControl)) {
                        return@runCatching null
                    }
                    SharedStack(reference, name, ItemShareKind.WYNNTILS, fakeStack.newInstance(item, "From chat") as ItemStack)
                }.getOrNull()
                if (decoded != null) items.add(decoded)
            }
            return items
        }
    }
}
