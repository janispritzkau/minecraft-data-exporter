package exporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class DataExporter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Map<Property<?>, String> blockStateProperties = new LinkedHashMap<>();
    private static final Set<Class<?>> blockClasses = new LinkedHashSet<>();
    private static final Set<Class<?>> itemClasses = new LinkedHashSet<>();
    private static final Set<Class<?>> enumClasses = new LinkedHashSet<>();

    public static void main(String[] args) throws IOException {
        LOGGER.info("preparing export");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        var export = new JsonObject();
        exportBlockStateProperties(export);
        exportBlocks(export);
        exportBlockClasses(export);
        exportItems(export);
        exportItemClasses(export);
        exportPackets(export);
        exportEnumClasses(export);
        Files.writeString(Path.of("export.json"), GSON.toJson(export));
        LOGGER.info("export finished");
    }

    private static void exportBlockStateProperties(JsonObject export) {
        LOGGER.info("exporting block state properties");

        for (var field : BlockStateProperties.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!Property.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            try {
                var property = (Property<?>) field.get(BlockStateProperties.class);
                blockStateProperties.put(property, field.getName());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        var properties = new JsonObject();
        for (var entry : blockStateProperties.entrySet()) {
            properties.add(entry.getValue(), exportBlockStateProperty(entry.getKey()));
        }
        export.add("blockStateProperties", properties);
    }

    private static void exportBlocks(JsonObject export) {
        LOGGER.info("exporting {} blocks", Registry.BLOCK.size());
        var blocks = new JsonArray();
        for (var block : Registry.BLOCK) {
            var blockExport = new JsonObject();
            blockExport.addProperty("name", Registry.BLOCK.getKey(block).toString());
            blockExport.addProperty("class", block.getClass().getSimpleName());
            var properties = new JsonArray();
            for (var property : block.getStateDefinition().getProperties()) {
                properties.add(blockStateProperties.get(property));
            }
            if (properties.size() > 0) blockExport.add("properties", properties);
            var defaultState = exportBlockState(block.defaultBlockState());
            if (defaultState.size() > 0) blockExport.add("defaultState", defaultState);
            blocks.add(blockExport);
            collectClasses(blockClasses, Block.class, block);
        }
        export.add("blocks", blocks);
    }

    private static void exportBlockClasses(JsonObject export) {
        LOGGER.info("exporting {} block classes", blockClasses.size());
        var classes = new JsonArray();
        for (var blockClass : blockClasses) {
            var classExport = new JsonObject();
            classExport.addProperty("name", blockClass.getSimpleName());
            var superclass = blockClass.getSuperclass();
            if (Block.class.isAssignableFrom(superclass))
                classExport.addProperty("extends", superclass.getSimpleName());
            var properties = new JsonObject();
            for (var field : blockClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!Property.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    properties.addProperty(field.getName(), blockStateProperties.get((Property<?>) field.get(blockClass)));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
            if (properties.size() > 0) classExport.add("properties", properties);
            classes.add(classExport);
        }
        export.add("blockClasses", classes);
    }

    private static void exportItems(JsonObject export) {
        LOGGER.info("exporting {} items", Registry.ITEM.size());
        var items = new JsonArray();
        for (var item : Registry.ITEM) {
            var itemExport = new JsonObject();
            itemExport.addProperty("name", Registry.ITEM.getKey(item).toString());
            itemExport.addProperty("class", item.getClass().getSimpleName());
            if (item.getMaxStackSize() != 64) itemExport.addProperty("maxStackSize", item.getMaxStackSize());
            if (item.getMaxDamage() != 0) itemExport.addProperty("maxDamage", item.getMaxDamage());
            if (item.hasCraftingRemainingItem())
                itemExport.addProperty("craftingRemainingItem", Registry.ITEM.getKey(item.getCraftingRemainingItem()).toString());
            var rarity = item.getRarity(item.getDefaultInstance());
            if (rarity != Rarity.COMMON) itemExport.addProperty("rarity", rarity.name().toLowerCase());
            if (item.isFireResistant()) itemExport.addProperty("isFireResistant", true);
            items.add(itemExport);
            collectClasses(itemClasses, Item.class, item);
        }
        export.add("items", items);
    }

    private static void exportItemClasses(JsonObject export) {
        LOGGER.info("exporting {} item classes", itemClasses.size());
        var classes = new JsonArray();
        for (var itemClass : itemClasses) {
            var classExport = new JsonObject();
            classExport.addProperty("name", itemClass.getSimpleName());
            var superclass = itemClass.getSuperclass();
            if (Item.class.isAssignableFrom(superclass)) classExport.addProperty("extends", superclass.getSimpleName());
            classes.add(classExport);
        }
        export.add("itemClasses", classes);
    }

    private static void exportPackets(JsonObject export) {
        LOGGER.info("exporting packets");
        var protocols = new JsonObject();
        for (var protocol : ConnectionProtocol.values()) {
            var flows = new JsonObject();
            for (var flow : PacketFlow.values()) {
                var packets = new JsonArray();
                var packetsById = protocol.getPacketsByIds(flow);
                for (int i = 0; i < packetsById.size(); i++) {
                    var clazz = packetsById.get(i);
                    var packet = new JsonObject();
                    packet.addProperty("name", getFullClassName(clazz));
                    packets.add(packet);
                }
                if (packets.size() > 0) flows.add(flow.name().toLowerCase(), packets);
            }
            protocols.add(protocol.name().toLowerCase(), flows);
        }
        export.add("packets", protocols);
    }

    private static void exportEnumClasses(JsonObject export) {
        LOGGER.info("exporting {} enum classes", enumClasses.size());
        var classes = new JsonArray();
        for (var enumClass : enumClasses) {
            var enumClassExport = new JsonObject();
            enumClassExport.addProperty("name", getFullClassName(enumClass));
            var constants = new JsonArray();
            for (var enumConstant : enumClass.getEnumConstants()) {
                var constant = new JsonObject();
                constant.addProperty("name", ((Enum<?>) enumConstant).name());
                constants.add(constant);
            }
            enumClassExport.add("constants", constants);
            classes.add(enumClassExport);
        }
        export.add("enumClasses", classes);
    }

    private static JsonObject exportBlockStateProperty(Property<?> property) {
        var export = new JsonObject();
        export.addProperty("name", property.getName());
        if (property instanceof BooleanProperty) {
            export.addProperty("type", "boolean");
        } else if (property instanceof EnumProperty) {
            export.addProperty("type", "enum");
            export.addProperty("class", getFullClassName(property.getValueClass()));
            if (property.getPossibleValues().size() != property.getValueClass().getEnumConstants().length) {
                var values = new JsonArray();
                for (var value : property.getPossibleValues()) values.add(((Enum<?>) value).name());
                export.add("values", values);
            }
            enumClasses.add(property.getValueClass());
        } else if (property instanceof IntegerProperty) {
            export.addProperty("type", "integer");
            var values = property.getPossibleValues().stream().mapToInt(x -> (Integer) x).toArray();
            export.addProperty("min", Arrays.stream(values).min().orElseThrow());
            export.addProperty("max", Arrays.stream(values).max().orElseThrow());
        }
        return export;
    }

    private static JsonObject exportBlockState(BlockState blockState) {
        var state = new JsonObject();
        for (var property : blockState.getProperties()) {
            if (blockState.getValue(property) == property.getPossibleValues().iterator().next()) continue;
            var name = blockStateProperties.get(property);
            if (property instanceof BooleanProperty booleanProperty) {
                state.addProperty(name, blockState.getValue(booleanProperty));
            } else if (property instanceof EnumProperty<? extends Enum<?>> enumProperty) {
                state.addProperty(name, blockState.getValue(enumProperty).name());
            } else if (property instanceof IntegerProperty integerProperty) {
                state.addProperty(name, blockState.getValue(integerProperty));
            } else {
                throw new RuntimeException("Unknown type of property");
            }
        }
        return state;
    }

    private static String getFullClassName(Class<?> clazz) {
        var hierarchy = new ArrayList<Class<?>>();
        while (clazz != null) {
            hierarchy.add(clazz);
            clazz = clazz.getEnclosingClass();
        }
        Collections.reverse(hierarchy);
        return hierarchy.stream().map(Class::getSimpleName).collect(Collectors.joining("."));
    }

    private static <T> void collectClasses(Set<Class<?>> classes, Class<?> baseClass, T value) {
        var classHierarchy = new ArrayList<Class<?>>();
        var clazz = (Class<?>) value.getClass();
        while (baseClass.isAssignableFrom(clazz)) {
            classHierarchy.add(clazz);
            clazz = clazz.getSuperclass();
        }
        Collections.reverse(classHierarchy);
        classes.addAll(classHierarchy);
    }
}
