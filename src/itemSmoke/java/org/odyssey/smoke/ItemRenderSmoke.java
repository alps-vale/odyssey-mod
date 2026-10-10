package org.odyssey.smoke;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Real GPU acceptance probe. Only the dedicated Gradle run sets its output path. */
public final class ItemRenderSmoke implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        String destination = System.getProperty("odyssey.itemSmoke.output");
        if (destination == null) return;
        AtomicInteger ticks = new AtomicInteger();
        AtomicBoolean packReloaded = new AtomicBoolean();
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            int tick = ticks.incrementAndGet();
            if (tick < 40 || tick < 0) return;
            try {
                if (Files.exists(minecraft.gameDirectory.toPath().resolve("resourcepacks/wynncraft.zip")) && packReloaded.compareAndSet(false, true)) {
                    var repository = minecraft.getResourcePackRepository();
                    repository.reload();
                    List<String> selected = new ArrayList<>(repository.getSelectedIds());
                    if (!selected.contains("file/wynncraft.zip")) selected.add("file/wynncraft.zip");
                    repository.setSelected(selected);
                    System.out.println("[Odyssey Item Smoke] Resource packs: " + repository.getSelectedIds());
                    ticks.set(Integer.MIN_VALUE);
                    minecraft.reloadResourcePacks().whenComplete((ignored, error) -> minecraft.execute(() -> ticks.set(40)));
                    return;
                }
                String encoded = null;
                if (FabricLoader.getInstance().isModLoaded("wynntils")) {
                    encoded = encodedFixture();
                    if (encoded == null && tick < 1200) return;
                    if (encoded == null) throw new IllegalStateException("Wynntils gear registry did not load");
                }
                ticks.set(Integer.MIN_VALUE);
                ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("Tooltip render fixture").withStyle(ChatFormatting.LIGHT_PURPLE));
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("Native tooltip colours and layout"),
                    Component.literal("+42% Walk Speed").withStyle(ChatFormatting.GREEN),
                    Component.literal("-10% Health Regen").withStyle(ChatFormatting.RED),
                    Component.literal("Legendary Item").withStyle(ChatFormatting.AQUA))));
                String reference = "[Tooltip fixture]";
                Component body = Component.literal(reference).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowItem(stack)));
                String content = encoded == null ? reference : encoded + " " + reference;
                int expected = encoded == null ? 1 : 2;
                Class<?> capture = Class.forName("org.odyssey.mod.item.ItemSharing");
                Method method = capture.getMethod("capture", List.class, String.class, Function1.class);
                Function1<List<?>, Unit> complete = shares -> {
                    try {
                        if (shares.size() != expected) throw new IllegalStateException("Wrong number of captured shares: " + shares.size());
                        Path output = Path.of(destination);
                        Files.createDirectories(output.getParent());
                        for (Object share : shares) {
                            String png = (String) share.getClass().getMethod("getPng").invoke(share);
                            if (png == null) throw new IllegalStateException("Tooltip render timed out or exceeded bounds");
                            String kind = share.getClass().getMethod("getKind").invoke(share).toString();
                            Path file = kind.equals("WYNNTILS") ? output.resolveSibling("tooltip-wynntils.png") : output;
                            Files.write(file, Base64.getDecoder().decode(png));
                            System.out.println("[Odyssey Item Smoke] " + kind + " tooltip PNG PASS");
                        }
                        Files.writeString(output.resolveSibling("PASS"), "Native item-sharing render passed\n");
                    } catch (Exception failure) {
                        failure.printStackTrace();
                        System.out.println("[Odyssey Item Smoke] FAIL");
                    } finally {
                        minecraft.execute(minecraft::stop);
                    }
                    return Unit.INSTANCE;
                };
                method.invoke(capture.getField("INSTANCE").get(null), List.of(body), content, complete);
            } catch (Exception failure) {
                failure.printStackTrace();
                System.out.println("[Odyssey Item Smoke] FAIL");
                minecraft.stop();
            }
        });
    }

    /** Build a fixture using Wynntils' own public item encoder, without a game account. */
    private static String encodedFixture() throws Exception {
        Class<?> models = Class.forName("com.wynntils.core.components.Models");
        Object gear = models.getField("Gear").get(null);
        Object info = gear.getClass().getMethod("getGearInfoFromDisplayName", String.class).invoke(gear, "Stratiformis");
        if (info == null) return null;
        Class<?> statType = Class.forName("com.wynntils.models.stats.type.StatType");
        Class<?> possibleType = Class.forName("com.wynntils.models.stats.type.StatPossibleValues");
        Class<?> rangeType = Class.forName("com.wynntils.utils.type.RangedValue");
        Class<?> actualType = Class.forName("com.wynntils.models.stats.type.StatActualValue");
        Class<?> calculator = Class.forName("com.wynntils.models.stats.StatCalculator");
        List<?> possible = (List<?>) info.getClass().getMethod("getPossibleValueList").invoke(info);
        List<Object> rolls = new ArrayList<>();
        for (Object stat : possible) {
            Object type = possibleType.getMethod("statType").invoke(stat);
            int value = (int) calculator.getMethod("calculateStatValue", int.class, possibleType).invoke(null, 100, stat);
            Object range = calculator.getMethod("calculateInternalRollRange", possibleType, int.class, boolean.class).invoke(null, stat, value, false);
            rolls.add(actualType.getConstructor(statType, int.class, boolean.class, rangeType).newInstance(type, value, false, range));
        }
        Class<?> infoType = Class.forName("com.wynntils.models.gear.type.GearInfo");
        Class<?> instanceType = Class.forName("com.wynntils.models.gear.type.GearInstance");
        Class<?> requirementsType = Class.forName("com.wynntils.models.gear.type.GearInstanceRequirements");
        Object instance = instanceType.getMethod("create", infoType, List.class, List.class, int.class, Optional.class, requirementsType, Optional.class)
            .invoke(null, info, rolls, List.of(), 2, Optional.empty(), requirementsType.getField("UNKNOWN").get(null), Optional.empty());
        Object item = Class.forName("com.wynntils.models.items.items.game.GearItem").getConstructor(infoType, instanceType).newInstance(info, instance);
        Object encoding = models.getField("ItemEncoding").get(null);
        Class<?> itemType = Class.forName("com.wynntils.models.items.WynnItem");
        Class<?> settingsType = Class.forName("com.wynntils.models.items.encoding.type.EncodingSettings");
        Object result = encoding.getClass().getMethod("encodeItem", itemType, settingsType)
            .invoke(encoding, item, settingsType.getConstructor(boolean.class, boolean.class).newInstance(true, true));
        if ((boolean) result.getClass().getMethod("hasError").invoke(result)) throw new IllegalStateException("Wynntils fixture encoding failed");
        Object buffer = result.getClass().getMethod("getValue").invoke(result);
        return (String) encoding.getClass().getMethod("makeItemString", itemType, buffer.getClass()).invoke(encoding, item, buffer);
    }
}
