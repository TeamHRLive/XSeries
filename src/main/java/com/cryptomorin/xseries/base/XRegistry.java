package com.cryptomorin.xseries.base;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class XRegistry<XForm extends XModule<XForm, BukkitForm>, BukkitForm> implements Iterable<XForm> {
    /**
     * Lenient system that adds unknown values automatically on the fly.
     * Should be turned off for {@code DifferenceHelper}.
     */
    private static final boolean PERFORM_AUTO_ADD = true;

    /**
     * All entries are lowercase.
     * Entries that belong to "minecraft" namespace, are added without the namespace.
     */
    private final Map<String, XForm> nameMappings = new HashMap<>(20);
    private final Map<BukkitForm, XForm> bukkitToX = new IdentityHashMap<>(20);

    private final Class<BukkitForm> bukkitFormClass;
    private final Class<XForm> xFormClass;

    private final Supplier<Object> registrySupplier;
    private final BiFunction<BukkitForm, String[], XForm> creator;
    private final Function<Integer, XForm[]> createArray;
    private final String registryName;

    private final boolean supportsRegistry, isEnum;
    private boolean pulled = false;

    public XRegistry(Class<BukkitForm> bukkitFormClass, Class<XForm> xFormClass,
                     Supplier<Object> registrySupplier,
                     BiFunction<BukkitForm, String[], XForm> creator,
                     Function<Integer, XForm[]> createArray) {
        boolean supported;
        try {
            registrySupplier.get();
            supported = true;
        } catch (Throwable ex) {
            supported = false;
        }

        this.bukkitFormClass = bukkitFormClass;
        this.xFormClass = xFormClass;
        this.registryName = this.bukkitFormClass.getSimpleName();
        this.registrySupplier = registrySupplier;
        this.createArray = createArray;
        this.creator = creator;

        // Just because the registry exists, doesn't necessarily mean that
        // the class itself cannot be an enum.
        supportsRegistry = supported;
        isEnum = bukkitFormClass.isEnum();

        if (!supportsRegistry && !isEnum) {
            throw new IllegalStateException("Bukkit form is neither an enum nor a registry " + bukkitFormClass);
        }
    }

    public Map<String, XForm> nameMapping() {
        return nameMappings;
    }

    public Map<BukkitForm, XForm> mapping() {
        return bukkitToX;
    }

    private void pullValues() {
        if (!pulled) {
            pulled = true;
            // pullFieldNames();
            if (PERFORM_AUTO_ADD) pullSystemValues();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void processEnumLikeFields(Class<T> clazz, BiConsumer<String, T> consumer) {
        for (Field field : clazz.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == clazz &&
                    Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                try {
                    consumer.accept(field.getName(), (T) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void registerName(String name, XForm xForm) {
        nameMappings.put(normalizeName(name), xForm);
    }

    @SuppressWarnings("unused")
    private void pullFieldNames() {
        processEnumLikeFields(xFormClass, this::registerName);
        // processEnumLikeFields(xFormClass, (name, xForm) -> {
        //     xForm.setEnumName(normalizeName(name));
        //     registerName(name, xForm);
        // });
    }

    @SuppressWarnings("unchecked")
    private void pullSystemValues() {
        // Enum-life names
        if (isEnum) {
            for (BukkitForm bukkitForm : bukkitFormClass.getEnumConstants()) {
                std(bukkitForm);
            }
        } else {
            processEnumLikeFields(bukkitFormClass, (name, bukkit) -> {
                if (bukkit == null) return; // Experimental value of declared field.
                std(name, bukkit);
            });
        }

        // Minecraft namespaces
        if (supportsRegistry) {
            for (Keyed bukkitForm : bukkitRegistry()) {
                std((BukkitForm) bukkitForm);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BukkitForm valueOf(String name) {
        name = name.toUpperCase(Locale.ENGLISH).replace('.', '_');
        Class<Enum> clazz = (Class<Enum>) bukkitFormClass;
        return (BukkitForm) Enum.valueOf(clazz, name);
    }

    private Registry<?> bukkitRegistry() {
        return ((Registry<?>) registrySupplier.get());
    }

    @SuppressWarnings("unchecked")
    @Nullable
    protected BukkitForm getBukkit(String[] names) {
        for (String name : names) {
            if (supportsRegistry) {
                name = name.toLowerCase(Locale.ENGLISH);
                // if (!name.equals(name.toLowerCase(Locale.ENGLISH))) {
                //     // Namespaces don't support uppercase names which is for enums.
                //     continue;
                // }

                NamespacedKey key;
                if (name.contains(":")) key = NamespacedKey.fromString(name);
                else key = NamespacedKey.minecraft(name);

                Keyed bukkit = bukkitRegistry().get(key);
                return (BukkitForm) bukkit;
            } else {
                try {
                    return valueOf(name);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * @see #values()
     */
    @Unmodifiable
    public Collection<XForm> getValues() {
        pullValues();

        // Don't use nameMapping because it will return duplicates.
        return Collections.unmodifiableCollection(bukkitToX.values());
    }

    /**
     * @see #getValues()
     */
    public XForm[] values() {
        Collection<XForm> values = getValues();
        return values.toArray(createArray.apply(values.size()));
    }

    @NotNull
    @Override
    public Iterator<XForm> iterator() {
        return getValues().iterator();
    }

    public XForm getByBukkitForm(BukkitForm bukkit) {
        Objects.requireNonNull(bukkit, () -> "Cannot match null " + registryName);
        XForm mapping = bukkitToX.get(bukkit);

        if (mapping == null) {
            XForm xForm = std(bukkit);
            if (xForm == null) throw new UnsupportedOperationException("Unknown " + registryName + ": " + bukkit);
        }

        return mapping;
    }

    public Optional<XForm> getByName(@NotNull String name) {
        Objects.requireNonNull(name, () -> "Cannot match null " + registryName);
        if (name.isEmpty()) return Optional.empty();

        pullValues(); // Ensure field names are loaded too.
        return Optional.ofNullable(nameMappings.get(normalizeName(name)));
    }

    @ApiStatus.Internal
    public static String getName(Object bukkitForm) {
        Objects.requireNonNull(bukkitForm, "Cannot get name of a null bukkit form");

        if (bukkitForm instanceof Enum) {
            return ((Enum<?>) bukkitForm).name();
        } else if (bukkitForm instanceof Keyed) {
            return ((Keyed) bukkitForm).getKey().toString();
        } else {
            throw new AssertionError("Unknown xform type: " + bukkitForm + " (" + bukkitForm.getClass() + ')');
        }
    }

    /**
     * This method is here for legacy purposes.
     * <p>
     * Attempts to build the string like an enum name.<br>
     * Removes all the spaces, numbers and extra non-English characters. Also removes some config/in-game based strings.
     * While this method is hard to maintain, it's extremely efficient. It's approximately more than x5 times faster than
     * the normal RegEx + String Methods approach for both formatted and unformatted material names.
     *
     * @param name the sound name to format.
     * @return an enum name.
     * @since 1.0.0
     */
    @SuppressWarnings("unused")
    @NotNull
    private static String format(@NotNull String name) {
        int len = name.length();
        char[] chs = new char[len];
        int count = 0;
        boolean appendUnderline = false;

        for (int i = 0; i < len; i++) {
            char ch = name.charAt(i);

            if (!appendUnderline && count != 0 && (ch == '-' || ch == ' ' || ch == '_') && chs[count] != '_')
                appendUnderline = true;
            else {
                boolean number = false;
                // A few sounds have numbers in them.
                if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (number = (ch >= '0' && ch <= '9'))) {
                    if (appendUnderline) {
                        chs[count++] = '_';
                        appendUnderline = false;
                    }

                    if (number) chs[count++] = ch;
                    else chs[count++] = (char) (ch & 0x5f);
                }
            }
        }

        return new String(chs, 0, count);
    }

    private static String normalizeName(String name) {
        // TODO convert this into the format() method above.
        name = name.toLowerCase(Locale.ENGLISH);
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        name = name.replace('.', '_'); // This is very unlikely to cause a conflict.
        return name;
    }

    private XForm std(BukkitForm bukkit) {
        return std(null, bukkit);
    }

    private XForm std(@Nullable String extraFieldName, BukkitForm bukkit) {
        XForm xForm = bukkitToX.get(bukkit);
        if (xForm != null) return xForm;

        String name = getName(bukkit);

        if (getBukkit(new String[]{name}) == null) {
            // This happens in very rare cases, such as Biome's:
            // Biome CUSTOM = Bukkit.getUnsafe().getCustomBiome();
            // These values are not registered in the registry, but available for use.
            if (extraFieldName == null) {
                throw new IllegalArgumentException("Unknown standard bukkit form: " + bukkit + " named " + name);
            }
        }

        xForm = creator.apply(bukkit, extraFieldName == null ? new String[]{name} : new String[]{extraFieldName, name});
        if (!PERFORM_AUTO_ADD) return xForm;

        registerName(name, xForm);
        if (extraFieldName != null) registerName(extraFieldName, xForm);
        bukkitToX.put(bukkit, xForm);

        return xForm;
    }

    @ApiStatus.Internal
    public XForm std(String[] names) {
        // Doesn't matter if it's not supported, we should still create it.
        BukkitForm bukkit = getBukkit(names);
        XForm xForm = creator.apply(bukkit, names);
        return std(xForm);
    }

    @ApiStatus.Internal
    public XForm std(Function<BukkitForm, XForm> xForm, String[] names) {
        BukkitForm bukkit = getBukkit(names);
        return std(xForm.apply(bukkit));
    }

    @ApiStatus.Internal
    public XForm std(Function<BukkitForm, XForm> xForm, XForm tryOther, String[] names) {
        BukkitForm bukkit = getBukkit(names);
        if (bukkit == null) bukkit = tryOther.get();
        return std(xForm.apply(bukkit));
    }

    @ApiStatus.Internal
    public XForm std(XForm xForm) {
        for (String name : xForm.getNames()) {
            registerName(name, xForm);
        }
        if (xForm.isSupported()) bukkitToX.put(xForm.get(), xForm);
        return xForm;
    }
}
