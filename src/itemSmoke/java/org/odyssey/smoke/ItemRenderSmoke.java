package org.odyssey.smoke;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.odyssey.mod.chat.GuildChatDecorator;
import org.odyssey.mod.chat.GuildChatParser;
import org.odyssey.mod.chat.RankPillFactory;
import org.odyssey.mod.item.TooltipCapture;
import org.odyssey.mod.network.PresentationEntry;
import org.odyssey.mod.network.PresentationRepository;
import org.odyssey.mod.network.PresentationSnapshot;
import org.odyssey.mod.network.RankColors;
import org.odyssey.mod.network.RankPresentation;

/** Real GPU acceptance probe. Only the dedicated Gradle run sets its output path. */
public final class ItemRenderSmoke implements ClientModInitializer {
    /** Read by the smoke's Wynntils mixin, only while one tooltip is laid out. */
    public static boolean wynntilsEventsOffServer;
    private static final AtomicInteger fittedState = new AtomicInteger();

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
                    if (fittedWynntilsSmoke(minecraft, destination)) return;
                }
                ticks.set(Integer.MIN_VALUE);
                nativeNameColorSmoke();
                if (encoded != null) rarityMetadataSmoke(encoded);
                rewrittenGuildRankSmoke();
                ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
                stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(
                    Component.literal("Tooltip render fixture").withStyle(ChatFormatting.LIGHT_PURPLE)));
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("Native tooltip colours and layout"),
                    Component.literal("+42% Walk Speed").withStyle(ChatFormatting.GREEN),
                    Component.literal("-10% Health Regen").withStyle(ChatFormatting.RED),
                    Component.literal("Legendary Item").withStyle(ChatFormatting.AQUA))));
                String reference = "[Tooltip fixture]";
                Component body = Component.empty().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowItem(stack))).append(Component.literal(reference));
                ItemStack variant = stack.copy();
                variant.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Different roll fixture").withStyle(ChatFormatting.GREEN))));
                Component second = Component.literal(reference).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowItem(variant)));
                String content = (encoded == null ? "" : encoded + " ") + reference + " " + reference;
                int expected = encoded == null ? 2 : 3;
                Class<?> capture = Class.forName("org.odyssey.mod.item.ItemSharing");
                Method method = capture.getMethod("capture", List.class, String.class, Function1.class);
                // An unsupported wire version must not interrupt ordinary chat/capture.
                String unsupported = new String(Character.toChars(0xf0063));
                if (encoded != null) unsupported += encoded.substring(Character.charCount(encoded.codePointAt(0)));
                Function1<List<?>, Unit> fallback = shares -> {
                    if (!shares.isEmpty()) throw new IllegalStateException("Unsupported item version must use text fallback");
                    System.out.println("[Odyssey Item Smoke] Unsupported encoding fallback PASS");
                    return Unit.INSTANCE;
                };
                method.invoke(capture.getField("INSTANCE").get(null), List.of(), unsupported, fallback);
                AtomicBoolean rendered = new AtomicBoolean();
                Function1<List<?>, Unit> complete = shares -> {
                    try {
                        if (shares.size() != expected) throw new IllegalStateException("Wrong number of captured shares: " + shares.size());
                        Path output = Path.of(destination);
                        Files.createDirectories(output.getParent());
                        int nativeIndex = 0;
                        for (Object share : shares) {
                            String png = (String) share.getClass().getMethod("getPng").invoke(share);
                            if (png == null) throw new IllegalStateException("Tooltip render timed out or exceeded bounds");
                            String kind = share.getClass().getMethod("getKind").invoke(share).toString();
                            checkDrawnBounds(Base64.getDecoder().decode(png), kind);
                            int color = (int) share.getClass().getMethod("getColor").invoke(share);
                            int expectedColor = kind.equals("WYNNTILS") ? 0xAA00AA : 0xFF55FF;
                            if (color != expectedColor) throw new IllegalStateException("Wrong rarity colour: " + Integer.toHexString(color));
                            Path file = kind.equals("WYNNTILS") ? output.resolveSibling("tooltip-wynntils.png") :
                                (nativeIndex++ == 0 ? output : output.resolveSibling("tooltip-native-variant.png"));
                            Files.write(file, Base64.getDecoder().decode(png));
                            System.out.println("[Odyssey Item Smoke] " + kind + " tooltip PNG PASS");
                        }
                        rendered.set(true);
                    } catch (Exception failure) {
                        failure.printStackTrace();
                        System.out.println("[Odyssey Item Smoke] FAIL");
                        minecraft.execute(minecraft::stop);
                    }
                    return Unit.INSTANCE;
                };
                method.invoke(capture.getField("INSTANCE").get(null), List.of(body, second), content, complete);
                // The next message's tooltips wait for those slots; only deferred releases start them.
                AtomicBoolean waitedRendered = new AtomicBoolean();
                Function1<List<?>, Unit> waitingMessage = shares -> {
                    try {
                        if (!rendered.get() || shares.size() != expected) throw new IllegalStateException("Waiting item message completed out of order");
                        for (Object share : shares) {
                            if (share.getClass().getMethod("getPng").invoke(share) == null) throw new IllegalStateException("Waiting tooltip capture did not render");
                        }
                        System.out.println("[Odyssey Item Smoke] Waiting tooltip captures PASS");
                        waitedRendered.set(true);
                    } catch (Exception failure) {
                        failure.printStackTrace();
                        System.out.println("[Odyssey Item Smoke] FAIL");
                        minecraft.execute(minecraft::stop);
                    }
                    return Unit.INSTANCE;
                };
                method.invoke(capture.getField("INSTANCE").get(null), List.of(body, second), content, waitingMessage);
                Function1<List<?>, Unit> followingChat = shares -> {
                    try {
                        if (!rendered.get() || !waitedRendered.get() || !shares.isEmpty()) throw new IllegalStateException("Plain chat overtook the item render");
                        System.out.println("[Odyssey Item Smoke] Ordered chat completion PASS");
                        Files.writeString(Path.of(destination).resolveSibling("PASS"), "Native item-sharing render and ordering passed\n");
                    } catch (Exception failure) {
                        failure.printStackTrace();
                        System.out.println("[Odyssey Item Smoke] FAIL");
                    } finally {
                        minecraft.execute(minecraft::stop);
                    }
                    return Unit.INSTANCE;
                };
                method.invoke(capture.getField("INSTANCE").get(null), List.of(), "following ordinary chat", followingChat);
            } catch (Exception failure) {
                failure.printStackTrace();
                System.out.println("[Odyssey Item Smoke] FAIL");
                minecraft.stop();
            }
        });
    }

    /** Wynntils shrinks and moves tooltips taller than the player's window; captures must not. */
    private static boolean fittedWynntilsSmoke(Minecraft minecraft, String destination) throws Exception {
        if (fittedState.get() == 2) return false;
        if (fittedState.getAndSet(1) == 1) return true;
        ItemStack stack = wynntilsStack(encodedFixture("Thrundacrack"));
        int lines = stack.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL).size();
        if (lines * 10 + 10 <= minecraft.getWindow().getGuiScaledHeight())
            throw new IllegalStateException("The window is too tall for Wynntils to fit the tooltip");
        CompletableFuture<String> fitted;
        wynntilsEventsOffServer = true;
        try {
            fitted = TooltipCapture.INSTANCE.capture(stack);
        } finally {
            wynntilsEventsOffServer = false;
        }
        fitted.thenCombine(TooltipCapture.INSTANCE.capture(stack), (withEvents, plain) -> {
            try {
                if (withEvents == null || plain == null) throw new IllegalStateException("Thrundacrack did not render");
                byte[] png = Base64.getDecoder().decode(withEvents);
                Files.write(Path.of(destination).resolveSibling("tooltip-wynntils-fitted.png"), png);
                checkDrawnBounds(png, "Fitted WYNNTILS");
                if (!withEvents.equals(plain)) throw new IllegalStateException("Wynntils fitting changed the captured tooltip");
                System.out.println("[Odyssey Item Smoke] Wynntils-fitted tooltip matches its unfitted capture PASS");
                return true;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).whenComplete((passed, failure) -> minecraft.execute(() -> {
            if (failure == null) {
                fittedState.set(2);
                return;
            }
            failure.printStackTrace();
            System.out.println("[Odyssey Item Smoke] FAIL");
            minecraft.stop();
        }));
        return true;
    }

    private static ItemStack wynntilsStack(String code) throws Exception {
        Class<?> access = Class.forName("org.odyssey.mod.item.ItemSharing$WynntilsAccess");
        var constructor = access.getDeclaredConstructor();
        constructor.setAccessible(true);
        var decode = access.getDeclaredMethod("decode", String.class);
        decode.setAccessible(true);
        Object shared = ((List<?>) decode.invoke(constructor.newInstance(), code)).get(0);
        var stack = shared.getClass().getDeclaredMethod("getStack");
        stack.setAccessible(true);
        return (ItemStack) stack.invoke(shared);
    }

    /** Undrawn pixels stay transparent and every edge of the image holds a drawn pixel. */
    private static void checkDrawnBounds(byte[] png, String kind) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        int width = image.getWidth();
        int height = image.getHeight();
        boolean transparent = false, top = false, bottom = false, left = false, right = false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (image.getRGB(x, y) >>> 24 == 0) {
                    transparent = true;
                    continue;
                }
                top |= y == 0;
                bottom |= y == height - 1;
                left |= x == 0;
                right |= x == width - 1;
            }
        }
        if (!transparent || !(top && bottom && left && right))
            throw new IllegalStateException(kind + " tooltip PNG is not cropped to transparent-backed drawn pixels");
    }

    private static void nativeNameColorSmoke() throws Exception {
        Class<?> sharing = Class.forName("org.odyssey.mod.item.ItemSharing");
        Method color = sharing.getDeclaredMethod("nameColor", Component.class);
        color.setAccessible(true);
        Object instance = sharing.getField("INSTANCE").get(null);
        Component suffix = Component.literal(" coloured suffix").withStyle(ChatFormatting.LIGHT_PURPLE);
        Component[] names = {
            Component.literal("Default prefix").append(suffix),
            Component.empty().withStyle(ChatFormatting.AQUA).append("Inherited name"),
            Component.literal(" ").append(Component.literal("Nested name").withStyle(ChatFormatting.LIGHT_PURPLE)),
            Component.empty()
        };
        int[] expected = {0xFFFFFF, 0x55FFFF, 0xFF55FF, 0xFFFFFF};
        for (int index = 0; index < names.length; index++) {
            if ((int) color.invoke(instance, names[index]) != expected[index]) {
                throw new IllegalStateException("Wrong native name colour for fixture " + index);
            }
        }
        System.out.println("[Odyssey Item Smoke] Native first-visible name colours PASS");
    }

    /** Exercise the installed Wynntils component rewrite, not a second decoder implementation. */
    private static void rewrittenGuildRankSmoke() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("wynntils")) return;
        var hover = new HoverEvent.ShowText(Component.literal("Festival Rat's real name is Colossal_Rat"));
        var bodyHover = new HoverEvent.ShowText(Component.literal("Profession speed worlds"));
        Component original = Component.empty().withStyle(ChatFormatting.AQUA)
            .append(Component.literal("\uE001")).append(" ")
            .append(Component.literal("\uE002").append(Component.literal("\uE003").withStyle(ChatFormatting.BLACK)))
            .append(" ")
            .append(Component.empty().withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal("Festival Rat").withStyle(style -> style.withHoverEvent(hover))).append(":"))
            .append(Component.literal(" [WynnExtras] ")
                .append(Component.literal("[ProfSpeed]").withStyle(style -> style.withHoverEvent(bodyHover)))
                .append(" EU15 [LootChest] NA25"));
        Class<?> styledText = Class.forName("com.wynntils.core.text.StyledText");
        Object styled = styledText.getMethod("fromComponent", Component.class).invoke(null, original);
        Component rewritten = (Component) styledText.getMethod("getComponent").invoke(styled);
        if (GuildChatParser.INSTANCE.parse(original) == null || GuildChatParser.INSTANCE.parse(rewritten) != null)
            throw new IllegalStateException("Guild wire parser must accept only the original validated tree");
        var previous = PresentationRepository.INSTANCE.snapshot();
        var role = new RankPresentation("Highlander", new RankColors(0x7788FF, null, null));
        String uuid = "22222222-2222-4222-8222-222222222222";
        PresentationRepository.INSTANCE.replace(new PresentationSnapshot(1,
            java.util.Map.of(uuid, new PresentationEntry(uuid, "Colossal_Rat", role))));
        try {
            GuildChatDecorator.INSTANCE.withGuildMessage(original, (Function0<Unit>) () -> {
                Component decorated = GuildChatDecorator.INSTANCE.decorate(rewritten);
                if (!decorated.getString().contains(RankPillFactory.INSTANCE.rank(role).getString()) ||
                    !decorated.getString().endsWith(" [WynnExtras] [ProfSpeed] EU15 [LootChest] NA25"))
                    throw new IllegalStateException("Wynntils rewrite lost the linked rank or body");
                AtomicBoolean keptHover = new AtomicBoolean();
                decorated.visit((net.minecraft.network.chat.FormattedText.StyledContentConsumer<Unit>) (style, text) -> {
                    if (text.equals("[ProfSpeed]") && bodyHover.equals(style.getHoverEvent())) keptHover.set(true);
                    return Optional.empty();
                }, net.minecraft.network.chat.Style.EMPTY);
                if (!keptHover.get()) throw new IllegalStateException("Guild rewrite lost the item/bomb-share hover");
                return Unit.INSTANCE;
            });
            if (GuildChatDecorator.INSTANCE.decorate(rewritten) != rewritten)
                throw new IllegalStateException("Raw guild header leaked past its own delivery");
            System.out.println("[Odyssey Item Smoke] Wynntils rewritten guild rank and hover PASS");
        } finally {
            PresentationRepository.INSTANCE.replace(previous);
        }
    }

    /** Build a fixture using Wynntils' own public item encoder, without a game account. */
    private static String encodedFixture() throws Exception {
        return encodedFixture("Stratiformis");
    }

    private static String encodedFixture(String name) throws Exception {
        Class<?> models = Class.forName("com.wynntils.core.components.Models");
        Object gear = models.getField("Gear").get(null);
        Object info = gear.getClass().getMethod("getGearInfoFromDisplayName", String.class).invoke(gear, name);
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
        return encodeFixture(item);
    }

    private static String encodeFixture(Object item) throws Exception {
        Class<?> models = Class.forName("com.wynntils.core.components.Models");
        Object encoding = models.getField("ItemEncoding").get(null);
        Class<?> itemType = Class.forName("com.wynntils.models.items.WynnItem");
        Class<?> settingsType = Class.forName("com.wynntils.models.items.encoding.type.EncodingSettings");
        Object result = encoding.getClass().getMethod("encodeItem", itemType, settingsType)
            .invoke(encoding, item, settingsType.getConstructor(boolean.class, boolean.class).newInstance(true, true));
        if ((boolean) result.getClass().getMethod("hasError").invoke(result)) throw new IllegalStateException("Wynntils fixture encoding failed");
        Object buffer = result.getClass().getMethod("getValue").invoke(result);
        return (String) encoding.getClass().getMethod("makeItemString", itemType, buffer.getClass()).invoke(encoding, item, buffer);
    }

    private static void rarityMetadataSmoke(String mythic) throws Exception {
        Class<?> requirements = Class.forName("com.wynntils.models.gear.type.GearRequirements");
        Object required = requirements.getConstructor(int.class, Optional.class, List.class, Optional.class)
            .newInstance(100, Optional.empty(), List.of(), Optional.empty());
        Class<?> instanceRequirements = Class.forName("com.wynntils.models.gear.type.GearInstanceRequirements");
        Class<?> durability = Class.forName("com.wynntils.utils.type.CappedValue");
        Class<?> type = Class.forName("com.wynntils.models.gear.type.GearType");
        Object crafted = Class.forName("com.wynntils.models.items.items.game.CraftedGearItem")
            .getConstructors()[0].newInstance("Crafted colour fixture", type.getField("CHESTPLATE").get(null),
                null, 0, 4000, List.of(), List.of(), required, List.of(), List.of(), List.of(), 0,
                instanceRequirements.getField("UNKNOWN").get(null), durability.getConstructor(int.class, int.class).newInstance(100, 100), 0);
        List<String> codes = List.of(mythic, encodedFixture("Blue Mask"), encodeFixture(crafted));
        Class<?> access = Class.forName("org.odyssey.mod.item.ItemSharing$WynntilsAccess");
        var constructor = access.getDeclaredConstructor();
        constructor.setAccessible(true);
        var decode = access.getDeclaredMethod("decode", String.class);
        decode.setAccessible(true);
        List<?> shares = (List<?>) decode.invoke(constructor.newInstance(), String.join(" ", codes));
        if (shares.size() != 3) throw new IllegalStateException("Rarity fixtures did not decode");
        int[] colors = {0xAA00AA, 0x55FFFF, 0x00AAAA};
        for (int index = 0; index < shares.size(); index++) {
            Object share = shares.get(index);
            var color = share.getClass().getDeclaredMethod("getColor");
            var original = share.getClass().getDeclaredMethod("getEncoded");
            color.setAccessible(true);
            original.setAccessible(true);
            if ((int) color.invoke(share) != colors[index]) throw new IllegalStateException("Wrong rarity colour for fixture " + index);
            if (!codes.get(index).equals(original.invoke(share))) throw new IllegalStateException("Item code changed during decoding");
        }
        System.out.println("[Odyssey Item Smoke] Mythic, legendary and crafted rarity colours and original codes PASS");
    }
}
